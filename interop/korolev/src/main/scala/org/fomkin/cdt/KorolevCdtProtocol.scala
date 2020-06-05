package org.fomkin.cdt

import java.net.URI
import java.util.concurrent.atomic.AtomicLong

import korolev.data.ByteVector
import korolev.effect.{AsyncTable, Effect, Queue}
import korolev.effect.syntax._
import korolev.http.HttpClient
import korolev.http.protocol.WebSocketProtocol.Frame
import korolev.web.Path
import org.fomkin.cdt.protocol.Protocol

import scala.concurrent.ExecutionContext

class KorolevCdtProtocol[F[_]: Effect, J: Json](send: String => F[Unit],
                                                resultsTable: AsyncTable[F, Long, J]) extends Protocol[F, J] {

  private val atomicLong = new AtomicLong(0L)

  def runCommand[R](domain: String, name: String, params: J, mapResult: J => R): F[R] =
    for {
      id <- Effect[F].delay(atomicLong.getAndIncrement())
      _ <- send(
        Json[J].stringify(
          Json[J].obj(
            "id" -> Json[J].long(id),
            "method" -> Json[J].string(s"$domain.$name"),
            "params" -> params
          )
        )
      )
      result <- resultsTable.get(id)
    } yield {
      try {
        mapResult(result)
      } catch {
        case error: Throwable =>
          throw new Exception(s"Unable to parse result: $result", error)
      }
    }
}

object KorolevCdtProtocol {

  // TODO rewrite this piece of shit
  def runBrowser[F[_]: Effect](chromium: String = "chromium", port: Int = 9222, headless: Boolean = true): F[(URI, F[Int])] = {
    Effect[F].promise { cb =>
      import sys.process._
      val headlessFlag =
        if (headless) "--headless"
        else ""
      lazy val process: Process = s"$chromium $headlessFlag --remote-debugging-port=$port".run(ProcessLogger(
        _ => (), {
          case s"DevTools listening on $uri" =>
            val join = Effect[F].promise[Int] { cb2 =>
               val thread = new Thread {
                override def run(): Unit = {
                  val exitCode = process.exitValue()
                  cb2(Right(exitCode))
                }
              }
              thread.start()
            }
            cb(Right((URI.create(uri), join)))
          case err =>
            //println(s"unhandled stderr: $err")
        }
      ))
      process
    }
  }

  def apply[F[_]: Effect, J: Json](uri: URI)(implicit ec: ExecutionContext): F[KorolevCdtProtocol[F, J]] = {
    val resultsTable = AsyncTable.empty[F, Long, J]
    val queue = Queue[F, String]()
    val outgoing = queue.stream.map[Frame] { message => Frame.Text(ByteVector.utf8(message)) }
    for {
      response <- HttpClient.webSocket(uri.getHost, uri.getPort, Path.fromString(uri.getPath), outgoing)
      incoming = response.body.collect {
        case Frame.Text(message, _) =>
          Json[J].unsafeParse(message.utf8String)
      }
      List(commandResults, events) = incoming.sort(2) { message =>
        if (Json[J].get(message, "id").nonEmpty) 0 else 1
      }
      _ <- commandResults
        .foreach { message =>
          val id = Json[J].unsafeToLong(Json[J].unsafeGet(message, "id"))
          val result = (Json[J].get(message, "result"), Json[J].get(message, "error")) match {
            case (Some(r), None) => Right(r)
            case (None, Some(e)) => Left(
              CdtProtocolError(
                Json[J].get(e, "code").flatMap(Json[J].toInt).getOrElse(0),
                Json[J].get(e, "message").flatMap(Json[J].toString).getOrElse("")
              )
            )
            case _ => Left(
              CdtProtocolError(
                code = 0,
                message = s"Unexpected response contains both `result` and `error` fields. $message"
              )
            )
          }
          resultsTable.putEither(id, result)
        }
        .start
      // TODO process events
      _ <- events
        .foreach { message =>
          Effect[F].delay(println(Json[J].stringify(message)))
        }
        .start
    } yield {
      new KorolevCdtProtocol(queue.offer, resultsTable)
    }
  }
}
