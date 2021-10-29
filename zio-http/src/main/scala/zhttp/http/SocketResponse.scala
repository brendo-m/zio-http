package zhttp.http

import io.netty.handler.codec.http.HttpHeaderNames
import zhttp.socket.SocketApp

import java.security.MessageDigest
import java.util.Base64

object SocketResponse {

  def from[R, E](headers: List[Header] = Nil, socketApp: SocketApp[R, E], req: Request): Response[R, E] = {
    val webSocketKey = req.getHeaderValue(HttpHeaderNames.SEC_WEBSOCKET_KEY) match {
      case Some(value) => value
      case None        => null
    }
    if (webSocketKey != null) {
      val wsHeader = secWebSocketAcceptHeader(webSocketKey)
      Response(
        status = Status.SWITCHING_PROTOCOLS,
        headers = headers ++ webSocketHeaders(wsHeader, req),
        data = HttpData.empty,
        attribute = HttpAttribute.fromSocket(socketApp),
      )
    } else {
      Response.fromHttpError(HttpError.BadRequest("missing WS Key Header"))
    }
  }

  private def webSocketHeaders(key: String, req: Request) = List(
    Header.custom(HttpHeaderNames.UPGRADE.toString(), "websocket"),
    Header.custom(HttpHeaderNames.CONNECTION.toString(), "upgrade"),
    Header.custom(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT.toString(), key),
    Header.custom(HttpHeaderNames.ORIGIN.toString(), req.url.asString), //TODO confirm origin header value
  )

  private def secWebSocketAcceptHeader(key: String) = {
    val globalUID   = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    val combinedKey = key + globalUID
    val sha1        = MessageDigest.getInstance("SHA-1")
    Base64.getEncoder.encodeToString(sha1.digest(combinedKey.getBytes()))
  }
}
