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
import akka.actor.{Actor, ActorSystem, Props, Scheduler}
import akka.dispatch.Await
import akka.util
import akka.util.Duration
import org.slf4j.LoggerFactory
import org.specs2.Specification
import org.specs2.specification._


trait HttpClientSpecs extends Specification {
  implicit val system = ActorSystem("specs")
  protected lazy val log = LoggerFactory.getLogger(getClass)
  def clientSpecs =

    "This spec exercises the HttpClient timeout logic" ^
      p ^
      Step(start()) ^
      "simple one-request dialog" ! oneRequest ^
      "connect to a non-existing server" ! illegalConnect ^
      "time-out request" ! timeoutRequest ^
      "idle-time-out connection" ! timeoutConnection ^
      end

  private class TestService extends Actor {
    protected def receive = {
      case RequestContext(HttpRequest(_, "/wait500", _, _, _), _, responder) => {
        context.system.scheduler.scheduleOnce(Duration.create(500, "millis")) { responder.complete(HttpResponse()) }
      }
      case RequestContext(HttpRequest(method, uri, _, _, _), _, responder) =>
        responder.complete(HttpResponse().withBody(method + "|" + uri))
    }
  }

  import HttpClient._
  import Await._
  implicit val timeout = system.settings.ActorTimeout
  
  private def oneRequest = {
    result(dialog()
      .send(HttpRequest(GET, "/yeah"))
      .end, timeout.duration)
      .bodyAsString mustEqual "GET|/yeah"
  }

  private def illegalConnect = {
    log.debug("illegalConnect:start")
    val f = dialog(16243)
      .send(HttpRequest(GET, "/"))
      .end
    f recover {
      case e => log.debug(e.toString)
    }
    result(f, timeout.duration) mustEqual "cc.spray.can.HttpClientException: " +
      "Could not connect to localhost:16243 due to java.net.ConnectException: Connection refused"
  }

  private def timeoutRequest = {
    log.debug("timeoutRequest:start")
    val f = dialog()
      .send(HttpRequest(GET, "/wait500"))
      .end
    f recover {
      case e => log.debug(e.toString)
    }
    result(f, timeout.duration) mustEqual "cc.spray.can.HttpClientException: Request timed out"
  }

  private def timeoutConnection = {
    log.debug("timeoutConnection:start")
    val f = dialog()
      .waitIdle(Duration("500 ms"))
      .send(HttpRequest(GET, "/"))
      .end
    f recover {
      case e => log.debug(e.toString)
    }
    result(f, timeout.duration) mustEqual "cc.spray.can.HttpClientException: " +
      "Cannot send request due to closed connection"
  }

  private def dialog(port: Int = 16242) =
    HttpDialog(host = "localhost", port = port, clientActorName = "client-test-client")

  private def start() {
    system.actorOf(Props(new TestService), name = "client-test-server")
    system.actorOf(Props(new HttpServer(ServerConfig(port = 16242, serviceActorName = "client-test-server", requestTimeout = 0))))
    system.actorOf(Props(new HttpClient(ClientConfig(requestTimeout = 100, timeoutCycle = 50, idleTimeout = 200, reapingCycle = 100))), name = "client-test-client")
  }
}
