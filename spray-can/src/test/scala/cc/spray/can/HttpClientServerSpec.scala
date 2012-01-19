/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can
import HttpMethods._
import akka.actor.{ Actor, Props, Scheduler }
import akka.dispatch.Await
import akka.util
import akka.util.Duration
import java.util.concurrent.CountDownLatch
import org.specs2._
import org.specs2.specification.Step

class HttpClientServerSpec extends Specification with HttpClientSpecs {
  def is =

    sequential ^
      "This spec exercises one HttpClient and one HttpServer instance" ^
      "with various request/response patterns" ^
      p ^
      Step(start()) ^
      "simple one-request dialog" ! oneRequestDialog ^
      "one-request dialog with HTTP/1.0 terminated-by-close reponse" ! terminatedByCloseDialog ^
      "request-response dialog" ! requestResponse ^
      "non-pipelined request-request dialog" ! nonPipelinedRequestRequest ^
      "pipelined request-request dialog" ! pipelinedRequestRequest ^
      "pipelined request-request dialog with response reordering" ! responseReorderingDialog ^
      "multi-request pipelined dialog with response reordering" ! multiRequestDialog ^
      "pipelined request-request dialog with HEAD requests" ! pipelinedRequestsWithHead ^
      "one-request dialog with a chunked response" ! oneRequestChunkedResponse ^
      "one-request dialog with a chunked request" ! oneRequestChunkedRequest ^
      "pipelined requests dialog with one chunked request" ! pipelinedRequestsWithChunkedRequest ^
      "pipelined requests dialog with one chunked response" ! pipelinedRequestsWithChunkedResponse ^
      "time-out request" ! timeoutRequest ^
      "idle-time-out connection" ! timeoutConnection ^
      p ^
      clientSpecs ^
      Step(system.shutdown)

  private class TestService extends Actor {
    var delayedResponse: RequestResponder = _
    protected def receive = {
      case RequestContext(HttpRequest(_, "/delayResponse", _, _, _), _, responder) =>
        delayedResponse = responder
      case RequestContext(HttpRequest(_, "/getThisAndDelayedResponse", _, _, _), _, responder) =>
        responder.complete(HttpResponse().withBody("secondResponse")) // first complete the second request
        delayedResponse.complete(HttpResponse().withBody("delayedResponse")) // then complete the first request
      case RequestContext(HttpRequest(_, path, _, _, _), _, responder) if path.startsWith("/multi/") =>
        val delay = (scala.math.random * 80.0).toLong
        context.system.scheduler.scheduleOnce(Duration.create(delay, "millis")) {
          responder.complete(HttpResponse().withBody(path.last.toString))
        }
      case RequestContext(HttpRequest(_, "/chunked", _, _, _), _, responder) => {
        val latch = new CountDownLatch(1)
        val chunker = responder.startChunkedResponse(HttpResponse(201, List(HttpHeader("Fancy", "cool"))))
        chunker.sendChunk(MessageChunk("1")).onSuccess {
          case () =>
            chunker.sendChunk(MessageChunk("-2345")).onSuccess {
              case () =>
                chunker.sendChunk(MessageChunk("-6789ABCD")).onSuccess {
                  case () =>
                    chunker.sendChunk(MessageChunk("-EFGHIJKLMNOPQRSTUVWXYZ")).onSuccess {
                      case () =>
                        latch.countDown()
                    }
                }
            }
        }
        latch.await()
        chunker.close()
      }
      case RequestContext(HttpRequest(_, "/wait200", _, _, _), _, responder) =>
        context.system.scheduler.scheduleOnce(Duration.create(200, "millis")) {
          responder.complete(HttpResponse())
        }
      case RequestContext(HttpRequest(_, "/terminatedByClose", _, _, _), _, responder) => responder.complete {
        HttpResponse(protocol = HttpProtocols.`HTTP/1.0`).withBody("This body is terminated by closing the connection!")
      }
      case RequestContext(HttpRequest(method, uri, _, body, _), _, responder) => responder.complete {
        HttpResponse().withBody(method + "|" + uri + (if (body.length == 0) "" else "|" + new String(body, "ASCII")))
      }
      case Timeout(_, _, _, _, _, complete) =>
        complete(HttpResponse().withBody("TIMEOUT"))
    }
  }

  import HttpClient._
  import Await._
  
  private def oneRequestDialog = {
    result(dialog.send(HttpRequest(uri = "/yeah"))
      .end, timeout.duration)
      .bodyAsString mustEqual "GET|/yeah"
  }

  private def terminatedByCloseDialog = {
    result(dialog
      .send(HttpRequest(uri = "/terminatedByClose"))
      .end, timeout.duration)
      .bodyAsString mustEqual "This body is terminated by closing the connection!"
  }

  private def requestResponse = {
    def respond(res: HttpResponse) = HttpRequest(POST).withBody("(" + res.bodyAsString + ")")

    result(dialog
      .send(HttpRequest(GET, "/abc"))
      .reply(respond)
      .end, timeout.duration)
      .bodyAsString mustEqual "POST|/|(GET|/abc)"
  }

  private def nonPipelinedRequestRequest = {
    result(dialog
      .send(HttpRequest(DELETE, "/abc"))
      .awaitResponse
      .send(HttpRequest(PUT, "/xyz"))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(", ") mustEqual "DELETE|/abc, PUT|/xyz"
  }

  private def pipelinedRequestRequest = {
    result(dialog
      .send(HttpRequest(DELETE, "/abc"))
      .send(HttpRequest(PUT, "/xyz"))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(", ") mustEqual "DELETE|/abc, PUT|/xyz"
  }

  private def responseReorderingDialog = {
    result(dialog
      .send(HttpRequest(uri = "/delayResponse"))
      .send(HttpRequest(uri = "/getThisAndDelayedResponse"))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(",") mustEqual "delayedResponse,secondResponse"
  }

  private def multiRequestDialog = {
    result(dialog
      .send((1 to 9).map(i => HttpRequest(uri = "/multi/" + i)))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(",") mustEqual "1,2,3,4,5,6,7,8,9"
  }

  private def pipelinedRequestsWithHead = {
    result(dialog
      .send(HttpRequest(DELETE, "/abc"))
      .send(HttpRequest(HEAD, "/def"))
      .send(HttpRequest(PUT, "/xyz"))
      .end, timeout.duration)
      .map { r =>
        (r.headers.collect({ case HttpHeader("Content-Length", cl) => cl }).head.toInt, r.bodyAsString)
      } mustEqual Seq((11, "DELETE|/abc"), (9, ""), (8, "PUT|/xyz"))
  }

  private def oneRequestChunkedResponse = {
    result(dialog
      .send(HttpRequest(GET, "/chunked"))
      .end, timeout.duration)
      .bodyAsString mustEqual "1-2345-6789ABCD-EFGHIJKLMNOPQRSTUVWXYZ"
  }

  private def oneRequestChunkedRequest = {
    result(dialog
      .sendChunked(HttpRequest(GET)) { chunker =>
        chunker.sendChunk(MessageChunk("1"))
        chunker.sendChunk(MessageChunk("2"))
        chunker.sendChunk(MessageChunk("3"))
        chunker.close()
      }
      .end, timeout.duration)
      .bodyAsString mustEqual "GET|/|123"
  }

  private def pipelinedRequestsWithChunkedRequest = {
    result(dialog
      .send(HttpRequest(DELETE, "/delete"))
      .sendChunked(HttpRequest(PUT, "/put")) { chunker =>
        chunker.sendChunk(MessageChunk("1"))
        chunker.sendChunk(MessageChunk("2"))
        chunker.sendChunk(MessageChunk("3"))
        chunker.close()
      }
      .send(HttpRequest(GET, "/get"))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(", ") mustEqual "DELETE|/delete, PUT|/put|123, GET|/get"
  }

  private def pipelinedRequestsWithChunkedResponse = {
    result(dialog
      .send(HttpRequest(POST, "/"))
      .send(HttpRequest(GET, "/chunked"))
      .send(HttpRequest(PUT, "/xyz"))
      .end, timeout.duration)
      .map(_.bodyAsString).mkString(", ") mustEqual "POST|/, 1-2345-6789ABCD-EFGHIJKLMNOPQRSTUVWXYZ, PUT|/xyz"
  }

  private def timeoutRequest = {
    result(dialog
      .send(HttpRequest(uri = "/wait200"))
      .end, timeout.duration)
      .bodyAsString mustEqual "TIMEOUT"
  }

  private def timeoutConnection = {
    val f = dialog
      .waitIdle(Duration("500 ms"))
      .send(HttpRequest())
      .end recover {
        case e => e.getMessage
      }
    result(f, timeout.duration) mustEqual "Cannot send request due to closed connection"
  }

  private def dialog = HttpDialog(host = "localhost", port = 17242, clientActorName = "server-test-client")

  private def start() {
    system.actorOf(Props(new TestService), name = "server-test-server")
    system.actorOf(Props(new HttpServer(ServerConfig(
      port = 17242,
      serviceActorName = "server-test-server",
      timeoutActorName = "server-test-server",
      requestTimeout = 100, timeoutCycle = 50,
      idleTimeout = 200, reapingCycle = 100))))
    system.actorOf(Props(new HttpClient(ClientConfig(requestTimeout = 1000))), name = "server-test-client")
  }
}




