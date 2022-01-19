package zhttp.service

import zhttp.http._
import zhttp.internal.{DynamicServer, HttpRunnableSpec}
import zhttp.service.server._
import zhttp.socket.{Socket, SocketApp, WebSocketFrame}
import zio.ZIO
import zio.duration._
import zio.stream.ZStream
import zio.test.Assertion.equalTo
import zio.test.TestAspect.timeout
import zio.test.assertM

object WebSocketServerSpec extends HttpRunnableSpec {

  override def spec = suiteM("Server") {
    app.as(List(websocketSpec)).useNow
  }.provideCustomLayerShared(env) @@ timeout(30 seconds)

  def websocketSpec = suite("WebSocket Server") {
    suite("connections") {
      testM("Multiple websocket upgrades") {
        val app = Http.fromZIO {
          Socket
            .collect[WebSocketFrame] { case WebSocketFrame.Text("FOO") =>
              ZStream.succeed(WebSocketFrame.text("BAR")) ++
                ZStream.succeed(WebSocketFrame.close(1000))
            }
            .toResponse
        }

        val client: SocketApp[Any] = Socket
          .collect[WebSocketFrame] { case frame: WebSocketFrame =>
            ZStream.succeed(frame)
          }
          .toSocketApp
          .onOpen(Socket.succeed(WebSocketFrame.Text("FOO")))
          .onClose(_ => ZIO.unit)
          .onError(thr => ZIO.die(thr))

        assertM(app.webSocketStatusCode(!! / "subscriptions", ss = client).repeatN(512))(
          equalTo(Status.SWITCHING_PROTOCOLS),
        )
      }
    }
  }

  private val env =
    EventLoopGroup.nio() ++ ServerChannelFactory.nio ++ DynamicServer.live ++ ChannelFactory.nio

  private val app = serve { DynamicServer.app }
}
