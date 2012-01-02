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

import akka.actor.Actor
import akka.dispatch.Promise

/**
 * A new instance of the `DefaultReceiverActor` is used as the receiver actor for `send` calls on an
 * [[cc.spray.can.HttpConnection]], that return a `Future[HttpResponse].
 */
class DefaultReceiverActor(future: Promise[HttpResponse], maxContentLength: Int) extends Actor {
  var body: Array[Byte] = _

  protected def receive = receiveResponse orElse receiveError

  protected def receiveResponse: Receive = {
    case x: HttpResponse =>
      future.success(x)
      context.stop(self)
    case start: ChunkedResponseStart => context.become(receiveChunkedResponse(start) orElse receiveError)
  }

  protected def receiveChunkedResponse(start: ChunkedResponseStart): Receive = {
    case x: MessageChunk => body match {
      case null => body = x.body
      case _ if body.length + x.body.length <= maxContentLength => body = body concat x.body
      case _ => future.failure(new HttpClientException("Response entity greater than configured " +
              "limit of " + maxContentLength + " bytes"))
    }
    case x: ChunkedResponseEnd =>
      future.success {
        HttpResponse(
          status = start.status,
          headers = start.headers,
          body = body,
          protocol = HttpProtocols.`HTTP/1.1`
        )
      }
      context.stop(self)
  }

  protected def receiveError: Receive = {
    case x: HttpClientException =>
      future.failure(x)
      context.stop(self)
  }
}
