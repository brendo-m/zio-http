package zhttp.service

import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.{HttpRequest, _}
import zhttp.http._
import zhttp.service.server.{ContentDecoder, WebSocketUpgrade}
import zio.{Chunk, Promise, Task, UIO, ZIO}

import java.net.{InetAddress, InetSocketAddress}

@Sharable
private[zhttp] final case class Handler[R](
  app: HttpApp[R, Throwable],
  runtime: HttpRuntime[R],
  config: Server.Config[R, Throwable],
) extends SimpleChannelInboundHandler[Any](true)
    with WebSocketUpgrade[R] { self =>

  type Ctx = ChannelHandlerContext

  import io.netty.util.AttributeKey

  private val DECODER_KEY: AttributeKey[ContentDecoder[Any, Throwable, Chunk[Byte], Any]] =
    AttributeKey.valueOf("decoderKey")
  private val COMPLETE_PROMISE: AttributeKey[Promise[Throwable, Any]]                     =
    AttributeKey.valueOf("completePromise")
  private val IS_FIRST: AttributeKey[Boolean] = AttributeKey.valueOf("isFirst")
  private val decoderState: AttributeKey[Any] =
    AttributeKey.valueOf("decoderState")
  private val REQUEST: AttributeKey[Request]  = AttributeKey.valueOf("request")
  private val BODY: AttributeKey[ByteBuf]     = AttributeKey.valueOf("body")

  override def channelRead0(ctx: Ctx, msg: Any): Unit = {
    implicit val iCtx: ChannelHandlerContext = ctx
    msg match {
      case jReq: HttpRequest    =>
        ctx.channel().config().setAutoRead(false)
        val request = new Request {
          override def method: Method                                 = Method.fromHttpMethod(jReq.method())
          override def url: URL                                       = URL.fromString(jReq.uri()).getOrElse(null)
          override def getHeaders: Headers                            = Headers.make(jReq.headers())
          override private[zhttp] def getBodyAsByteBuf: Task[ByteBuf] = ???

          override def decodeContent[R0, B](
            decoder: ContentDecoder[R0, Throwable, Chunk[Byte], B],
          ): ZIO[R0, Throwable, B] =
            ZIO.effectSuspendTotal {
              if (
                ctx
                  .channel()
                  .attr(DECODER_KEY)
                  .get() != null
              )
                ZIO.fail(ContentDecoder.Error.ContentDecodedOnce)
              else
                for {
                  p <- Promise.make[Throwable, B]
                  _ <- UIO {
                    ctx
                      .channel()
                      .attr(DECODER_KEY)
                      .setIfAbsent(decoder.asInstanceOf[ContentDecoder[Any, Throwable, Chunk[Byte], Any]])
                      .asInstanceOf[ContentDecoder[Any, Throwable, Chunk[Byte], B]]
                    ctx.channel().attr(COMPLETE_PROMISE).set(p.asInstanceOf[Promise[Throwable, Any]])
                    ctx.read(): Unit
                  }
                  b <- p.await
                } yield b
            }

          override def remoteAddress: Option[InetAddress] = {
            ctx.channel().remoteAddress() match {
              case m: InetSocketAddress => Some(m.getAddress)
              case _                    => None
            }
          }
        }
        ctx.channel().attr(REQUEST).set(request)
        unsafeRun(
          jReq,
          app,
          request,
        )
      case msg: LastHttpContent =>
        if (ctx.channel().attr(DECODER_KEY).get() != null)
          decodeContent(msg.content(), ctx.channel().attr(DECODER_KEY).get(), true)
        ctx.channel().config().setAutoRead(true): Unit
      case msg: HttpContent     =>
        if (ctx.channel().attr(DECODER_KEY).get() != null)
          decodeContent(msg.content(), ctx.channel().attr(DECODER_KEY).get(), false)
      case _                    => ???
    }

  }

  /**
   * Executes http apps
   */
  private def unsafeRun[A](
    jReq: HttpRequest,
    http: Http[R, Throwable, A, Response],
    a: A,
  )(implicit ctx: Ctx): Unit = {
    http.execute(a) match {
      case HExit.Effect(resM) =>
        unsafeRunZIO {
          resM.foldM(
            {
              case Some(cause) =>
                UIO {
                  ctx.fireChannelRead(
                    (Response.fromHttpError(HttpError.InternalServerError(cause = Some(cause))), jReq),
                  )
                }
              case None        =>
                UIO {
                  ctx.fireChannelRead((Response.status(Status.NOT_FOUND), jReq))
                }
            },
            res =>
              if (self.isWebSocket(res)) UIO(self.upgradeToWebSocket(ctx, jReq, res))
              else {
                for {
                  _ <- UIO {
                    ctx.fireChannelRead((res, jReq))
                  }
                } yield ()
              },
          )
        }

      case HExit.Success(res) =>
        if (self.isWebSocket(res)) {
          self.upgradeToWebSocket(ctx, jReq, res)
        } else {
          ctx.fireChannelRead((res, jReq)): Unit
        }

      case HExit.Failure(e) =>
        ctx.fireChannelRead((e, jReq)): Unit
      case HExit.Empty      =>
        ctx.fireChannelRead((Response.status(Status.NOT_FOUND), jReq)): Unit
    }

  }

  /**
   * Executes program
   */
  private def unsafeRunZIO(program: ZIO[R, Throwable, Any])(implicit ctx: Ctx): Unit =
    runtime.unsafeRun(ctx) {
      program
    }

  /**
   * Decodes content and executes according to the ContentDecoder provided
   */
  private def decodeContent(
    content: ByteBuf,
    decoder: ContentDecoder[Any, Throwable, Chunk[Byte], Any],
    isLast: Boolean,
  )(implicit ctx: ChannelHandlerContext): Unit = {
    decoder match {
      case ContentDecoder.Text =>
        val cBody = ctx.channel().hasAttr(BODY)
        if (cBody) {
          val cBody = ctx.channel().attr(BODY).get()
          ctx.channel().attr(BODY).set(cBody)
        } else {
          ctx.channel().attr(BODY).set(Unpooled.compositeBuffer().writeBytes(content))
        }
        if (isLast) {
          val body = ctx.channel().attr(BODY).get()
          unsafeRunZIO(
            ctx.channel().attr(COMPLETE_PROMISE).get().succeed(body.toString(HTTP_CHARSET)) <* UIO {
              ctx.channel().attr(BODY).set(null)
            },
          )
        } else {
          ctx.read(): Unit
        }

      case step: ContentDecoder.Step[_, _, _, _, _] =>
        if (!ctx.channel().hasAttr(IS_FIRST)) {
          ctx.channel().attr(decoderState).set(step.state)
          ctx.channel().attr(IS_FIRST).set(false)
        }

        val request = ctx.channel().attr(REQUEST).get()
        unsafeRunZIO(for {
          (publish, state) <- step
            .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, Chunk[Byte], Any]]
            .next(
              // content.array() fails with post request with body
              // Link: https://livebook.manning.com/book/netty-in-action/chapter-5/54
              Chunk.fromArray(ByteBufUtil.getBytes(content)),
              ctx.channel().attr(decoderState).get(),
              isLast,
              request.method,
              request.url,
              request.getHeaders,
            )
          _                <- publish match {
            case Some(out) => ctx.channel().attr(COMPLETE_PROMISE).get().succeed(out)
            case None      => ZIO.unit
          }
          _                <- UIO {
            ctx.channel().attr(decoderState).set(state)
            if (!isLast) {
              ctx.read()
            }
          }
        } yield ())
    }
  }
}
