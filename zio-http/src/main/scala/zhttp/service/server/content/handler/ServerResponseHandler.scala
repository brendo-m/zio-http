package zhttp.service.server.content.handler

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  DefaultHttpResponse,
  DefaultLastHttpContent,
  HttpHeaderNames,
  HttpResponse,
  HttpResponseStatus,
  HttpVersion,
  LastHttpContent,
}
import zhttp.core.Util
import zhttp.http.{HTTP_CHARSET, Header, HttpData, Response, Status}
import zhttp.service.{ChannelFuture, HttpRuntime}
import zhttp.service.server.ServerTimeGenerator
import zio.{UIO, ZIO}
import zio.stream.ZStream

/**
 * Transforms an internal Response into a HttpResponse.
 */
@Sharable
private[zhttp] case class ServerResponseHandler[R](runtime: HttpRuntime[R], serverTime: ServerTimeGenerator)
    extends SimpleChannelInboundHandler[Either[Throwable, Response[R, Throwable]]](false) {
  override def channelRead0(ctx: ChannelHandlerContext, response: Either[Throwable, Response[R, Throwable]]): Unit = {
    try {
      response match {
        case Left(err)               =>
          ctx.writeAndFlush(serverErrorResponse(err))
        case Right(internalResponse) =>
          internalResponse.status match {
            case Status.NOT_FOUND =>
              ctx.writeAndFlush(notFoundResponse)
//            case Status.NO_CONTENT =>
//              ctx.writeAndFlush(
//                LastHttpContent.EMPTY_LAST_CONTENT,
//              )
            case Status.OK        =>
              internalResponse.data match {
                case HttpData.Empty                => ctx.writeAndFlush(decodeResponse(internalResponse))
                case HttpData.Text(_, _)           =>
                  ctx.write(decodeResponse(internalResponse))
                case HttpData.BinaryByteBuf(data)  =>
                  ctx.writeAndFlush(new DefaultLastHttpContent(data))
                case HttpData.BinaryStream(stream) =>
                  runtime.unsafeRun(ctx) {
                    writeStreamContent(stream)(ctx)
                  }

                case HttpData.BinaryChunk(data) =>
                  ctx.writeAndFlush(data)

                case _ => ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
              }

            case _ =>
              ctx.writeAndFlush(decodeResponse(internalResponse))
          }
      }
    } catch {
      case err: Throwable => println(err)
    }

    ()

  }

  private def notFoundResponse: HttpResponse = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, false)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
    response
  }

  private def decodeResponse(res: Response[_, _]): HttpResponse = {
    if (res.attribute.memoize) decodeResponseCached(res) else decodeResponseFresh(res)
  }

  private def decodeResponseCached(res: Response[_, _]): HttpResponse = {
    val cachedResponse = res.cache
    // Update cache if it doesn't exist OR has become stale
    // TODO: add unit tests for server-time
    if (cachedResponse == null || (res.attribute.serverTime && serverTime.canUpdate())) {
      val jRes = decodeResponseFresh(res)
      res.cache = jRes
      jRes
    } else cachedResponse
  }

  private def decodeResponseFresh(res: Response[_, _]): HttpResponse = {
    val jHeaders = Header.disassemble(res.getHeaders)
    if (res.attribute.serverTime) jHeaders.set(HttpHeaderNames.DATE, serverTime.refreshAndGet())
    new DefaultHttpResponse(HttpVersion.HTTP_1_1, res.status.asJava, jHeaders)
  }

  private def serverErrorResponse(cause: Throwable): HttpResponse = {
    val content  = Util.prettyPrintHtml(cause)
    val response = new DefaultFullHttpResponse(
      HTTP_1_1,
      INTERNAL_SERVER_ERROR,
      Unpooled.copiedBuffer(content, HTTP_CHARSET),
    )
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length)
    response
  }

  /**
   * Writes Binary Stream data to the Channel
   */
  private def writeStreamContent[A](
    stream: ZStream[R, Throwable, ByteBuf],
  )(implicit ctx: ChannelHandlerContext): ZIO[R, Throwable, Unit] = {
    for {
      _ <- stream.foreach(c => UIO(ctx.writeAndFlush(c))).tap(i => ZIO(println(i)))
      _ <- ChannelFuture.unit(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))
    } yield ()
  }

}
