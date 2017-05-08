/*
 * Copyright 2014-2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.iep.lwc

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import com.netflix.atlas.akka.AccessLogger
import com.netflix.atlas.akka.DiagnosticMessage
import com.netflix.atlas.core.model.Datapoint
import com.netflix.atlas.core.model.DefaultSettings
import com.netflix.atlas.core.validation.ValidationResult
import com.netflix.atlas.json.Json
import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.histogram.BucketCounter
import com.netflix.spectator.api.histogram.BucketFunctions
import com.netflix.spectator.atlas.impl.Evaluator
import com.netflix.spectator.atlas.impl.Subscriptions
import com.netflix.spectator.atlas.impl.TagsValuePair
import com.typesafe.config.Config

import scala.concurrent.Future


/**
  * Takes messages from publish API handler and forwards them to LWC.
  */
class LwcPublishActor(config: Config, registry: Registry) extends Actor with ActorLogging {

  import LwcPublishActor._
  import scala.concurrent.duration._
  import com.netflix.atlas.webapi.PublishApi._

  import scala.concurrent.ExecutionContext.Implicits.global

  private implicit val materializer = ActorMaterializer()

  private val configUri = Uri(config.getString("netflix.iep.lwc.bridge.config-uri"))
  private val evalUri = Uri(config.getString("netflix.iep.lwc.bridge.eval-uri"))

  private val step = DefaultSettings.stepSize

  // Number of invalid datapoints received
  private val numReceivedCounter = BucketCounter.get(
    registry,
    registry.createId("atlas.db.numMetricsReceived"),
    BucketFunctions.ageBiasOld(step, TimeUnit.MILLISECONDS))

  // Number of invalid datapoints received
  private val numInvalidId = registry.createId("atlas.db.numInvalid")

  private val evaluator = new Evaluator()

  private val cancellable = context.system.scheduler.schedule(0.seconds, 10.seconds, self, Tick)

  def receive: Receive = {
    case Tick =>
      updateExpressions()
    case req @ PublishRequest(_, Nil, Nil, _, _) =>
      req.complete(DiagnosticMessage.error(StatusCodes.BadRequest, "empty payload"))
    case req @ PublishRequest(_, Nil, failures, _, _) =>
      updateStats(failures)
      val msg = FailureMessage.error(failures)
      sendError(req, StatusCodes.BadRequest, msg)
    case req @ PublishRequest(_, values, Nil, _, _) =>
      process(values)
      req.complete(HttpResponse(StatusCodes.OK))
    case req @ PublishRequest(_, values, failures, _, _) =>
      process(values)
      updateStats(failures)
      val msg = FailureMessage.partial(failures)
      sendError(req, StatusCodes.Accepted, msg)
  }

  override def postStop(): Unit = {
    cancellable.cancel()
    super.postStop()
  }

  private def updateExpressions(): Unit = {
    val request = HttpRequest(HttpMethods.GET, configUri)
    mkRequest("lwc-subs", request).onSuccess {
      case response if response.status == StatusCodes.OK =>
        response.entity.dataBytes.runReduce(_ ++ _).onSuccess {
          case data =>
            val exprs = Json.decode[Subscriptions](data.toArray).getExpressions
            evaluator.addGroupSubscriptions("all", exprs)
        }
      case response =>
        response.discardEntityBytes()
    }
  }

  private def process(values: List[Datapoint]): Unit = {
    import scala.collection.JavaConverters._
    val now = registry.clock().wallTime()
    values.foreach { v => numReceivedCounter.record(now - v.timestamp) }

    val timestamp = values.head.timestamp
    val payload = evaluator.eval("all", fixTimestamp(timestamp), values.map(toPair).asJava)

    val entity = HttpEntity(MediaTypes.`application/json`, Json.encode(payload))
    val request = HttpRequest(HttpMethods.POST, evalUri, Nil, entity)
    mkRequest("lwc-eval", request).onSuccess {
      case response => response.discardEntityBytes()
    }
  }

  private def mkRequest(name: String, request: HttpRequest): Future[HttpResponse] = {
    val accessLogger = AccessLogger.newClientLogger(name, request)
    Http()(context.system).singleRequest(request).andThen { case t => accessLogger.complete(t) }
  }

  private def fixTimestamp(t: Long): Long = t / step * step

  private def toPair(d: Datapoint): TagsValuePair = {
    import scala.collection.JavaConverters._
    new TagsValuePair(d.tags.asJava, d.value)
  }

  private def sendError(req: PublishRequest, status: StatusCode, msg: FailureMessage): Unit = {
    val entity = HttpEntity(MediaTypes.`application/json`, msg.toJson)
    req.complete(HttpResponse(status = status, entity = entity))
  }

  private def updateStats(failures: List[ValidationResult]): Unit = {
    failures.foreach {
      case ValidationResult.Pass           => // Ignored
      case ValidationResult.Fail(error, _) =>
        registry.counter(numInvalidId.withTag("error", error)).increment()
    }
  }
}

object LwcPublishActor {
  case object Tick
}
