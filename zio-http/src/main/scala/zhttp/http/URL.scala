package zhttp.http

import io.netty.handler.codec.http.{QueryStringDecoder, QueryStringEncoder}
import zhttp.http.URL.Fragment

import java.net.URI
import scala.jdk.CollectionConverters._
import scala.util.Try

final case class URL(
  path: Path,
  kind: URL.Location = URL.Location.Relative,
  queryParams: Map[String, List[String]] = Map.empty,
  fragment: Option[Fragment] = None,
) { self =>
  val host: Option[String] = kind match {
    case URL.Location.Relative      => None
    case abs: URL.Location.Absolute => Option(abs.host)
  }

  val port: Option[Int] = kind match {
    case URL.Location.Relative      => None
    case abs: URL.Location.Absolute => Option(abs.port)
  }

  private[zhttp] def relative: URL = self.kind match {
    case URL.Location.Relative => self
    case _                     => self.copy(kind = URL.Location.Relative)
  }

  def asString: String = URL.asString(self)
}
object URL {
  sealed trait Location
  object Location {
    case object Relative                                               extends Location
    final case class Absolute(scheme: Scheme, host: String, port: Int) extends Location
  }

  private def queryParams(query: String) = {
    if (query == null || query.isEmpty) {
      Map.empty[String, List[String]]
    } else {
      val decoder = new QueryStringDecoder(query, false)
      val params  = decoder.parameters()
      params.asScala.view.map { case (k, v) => (k, v.asScala.toList) }.toMap
    }
  }

  private def fromAbsoluteURI(uri: URI): Option[URL] = {

    def portFromScheme(scheme: Scheme): Int = scheme match {
      case Scheme.HTTP  => 80
      case Scheme.HTTPS => 443
    }

    for {
      scheme <- Scheme.fromString(uri.getScheme)
      host   <- Option(uri.getHost)
      path   <- Option(uri.getRawPath)
      port       = Option(uri.getPort).filter(_ != -1).getOrElse(portFromScheme(scheme))
      connection = URL.Location.Absolute(scheme, host, port)
    } yield URL(Path(path), connection, queryParams(uri.getRawQuery), Fragment.fromURI(uri))
  }

  private def fromRelativeURI(uri: URI): Option[URL] = for {
    path <- Option(uri.getRawPath)
  } yield URL(Path(path), Location.Relative, queryParams(uri.getRawQuery), Fragment.fromURI(uri))

  private def fromAbsoluteURI2(uri: URI): URL = {

    def portFromScheme(scheme: Scheme): Int = scheme match {
      case Scheme.HTTP  => 80
      case Scheme.HTTPS => 443
      case null         => -1
    }

    val scheme  = Scheme.fromString2(uri.getScheme)
    val uriPort = uri.getPort
    val port    = if (uriPort != -1) uriPort else portFromScheme(scheme)
    val host    = uri.getHost

    if (port != -1 && scheme != null && host != null)
      URL(
        Path(uri.getRawPath),
        URL.Location.Absolute(scheme, host, port),
        queryParams(uri.getRawQuery),
        Fragment.fromURI(uri),
      )
    else null
  }

  private def fromRelativeURI2(uri: URI): URL =
    URL(Path(uri.getRawPath), Location.Relative, queryParams(uri.getRawQuery), Fragment.fromURI(uri))

  def fromString(string: String): Either[HttpError, URL] = {
    def invalidURL = Left(HttpError.BadRequest(s"Invalid URL: $string"))
    for {
      url <- Try(new URI(string)).toEither match {
        case Left(_)      => invalidURL
        case Right(value) => Right(value)
      }
      url <- (if (url.isAbsolute) fromAbsoluteURI(url) else fromRelativeURI(url)) match {
        case None        => invalidURL
        case Some(value) => Right(value)
      }

    } yield url
  }
  /*
Changes:
1) wrapped in try catch
2) using other fromAbsoluteURI2(url) and fromRelativeURI2(url) which returns null or URL
   */
  def fromString2(string: String): URL                   = {
    try {
      val url = new URI(string)
      if (url.isAbsolute) fromAbsoluteURI2(url) else fromRelativeURI2(url)
    } catch { case _: Throwable => null }
  }
  /*
Changes:
1) wrapped in try catch and using fromAbsoluteURI(url).orNull
   */
  def fromString3(string: String): URL                   = {
    try {
      val url = new URI(string)
      if (url.isAbsolute) fromAbsoluteURI(url).orNull else fromRelativeURI(url).orNull
    } catch { case _: Throwable => null }
  }

  def asString(url: URL): String = {

    def path: String = {
      val encoder = new QueryStringEncoder(s"${url.path.asString}${url.fragment.fold("")(f => "#" + f.raw)}")
      url.queryParams.foreach { case (key, values) =>
        if (key != "") values.foreach { value => encoder.addParam(key, value) }
      }
      encoder.toString
    }

    url.kind match {
      case Location.Relative                     => path
      case Location.Absolute(scheme, host, port) =>
        if (port == 80 || port == 443) s"${scheme.asString}://$host$path"
        else s"${scheme.asString}://$host:$port$path"
    }
  }

  case class Fragment private (raw: String, decoded: String)
  object Fragment {
    def fromURI(uri: URI): Option[Fragment] = for {
      raw     <- Option(uri.getRawFragment)
      decoded <- Option(uri.getFragment)
    } yield Fragment(raw, decoded)
  }

  def root: URL = URL(!!)
}
