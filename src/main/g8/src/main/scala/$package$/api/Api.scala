package $package$.api

import akka.event.Logging._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.interop._
import play.api.libs.json.JsObject
import javax.ws.rs.core.MediaType
import javax.ws.rs.{Consumes, POST, Path, Produces}
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import zio._
import zio.config.ZConfig
$if(add_server_sent_events_endpoint.truthy || add_websocket_endpoint.truthy)$
import zio.interop.reactivestreams._
import akka.stream.scaladsl.Source
$endif$
$if(add_server_sent_events_endpoint.truthy)$
import akka.http.scaladsl.model.sse.ServerSentEvent
import scala.concurrent.duration._
$endif$
$if(add_websocket_endpoint.truthy)$
import akka.stream.scaladsl.{ Flow, Sink }
import akka.actor.ActorSystem
import akka.http.javadsl.model.ws.BinaryMessage
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import scala.util.{ Try, Success, Failure }
$endif$

object Api {

  trait Service {
    def routes: Route
  }

  val live: ZLayer[ZConfig[HttpServer.Config]$if(add_websocket_endpoint.truthy)$ with Has[ActorSystem]$endif$ with ItemRepository, Nothing, Api] = ZLayer.fromFunction(env =>
    new Service with JsonSupport with ZIOSupport {

      def routes: Route = itemRoute  ~  SwaggerDocService.routes

      implicit val domainErrorResponse: ErrorResponse[DomainError] = {
        case RepositoryError(_) => HttpResponse(StatusCodes.InternalServerError)
        case ValidationError(_) => HttpResponse(StatusCodes.BadRequest)
      }

      @Path("/echoenumeratum")
      val itemRoute: Route =
        pathPrefix("items") {
          logRequestResult(("items", InfoLevel)) {
            pathEnd {
              get {
                complete(ApplicationService.getItems.provide(env))
              } ~
              @POST
              @Consumes(Array(MediaType.APPLICATION_JSON))
              @Produces(Array(MediaType.APPLICATION_JSON))
              @Operation(summary = "Add integers", description = "Add integers")
              post {
                entity(Directives.as[CreateItemRequest]) { req =>
                  ApplicationService
                    .addItem(req.name, req.price)
                    .provide(env)
                    .map { id =>
                      complete {
                        Item(id, req.name, req.price)
                      }
                    }
                }
              }
            } ~
            @Path("/{username}")
            path(LongNumber) {
              itemId =>
                delete {
                  complete(
                    ApplicationService
                      .deleteItem(ItemId(itemId))
                      .provide(env)
                      .as(JsObject.empty)
                  )
                } ~
                get {
                  complete(ApplicationService.getItem(ItemId(itemId)).provide(env))
                } ~
                patch {
                  entity(Directives.as[PartialUpdateItemRequest]) { req =>
                    complete(
                      ApplicationService
                        .partialUpdateItem(ItemId(itemId), req.name, req.price)
                        .provide(env)
                        .as(JsObject.empty)
                    )
                  }
                } ~
                put {
                  entity(Directives.as[UpdateItemRequest]) { req =>
                    complete(
                      ApplicationService
                        .updateItem(ItemId(itemId), req.name, req.price)
                        .provide(env)
                        .as(JsObject.empty)
                    )
                  }
                }
            }
          }
        } $if(add_server_sent_events_endpoint.truthy)$ ~

          pathPrefix("sse" / "items") {
            import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

            logRequestResult(("sse/items", InfoLevel)) {
              pathPrefix("deleted") {
                get {
                  complete {
                    ApplicationService.deletedEvents.toPublisher
                      .map(p =>
                        Source
                          .fromPublisher(p)
                          .map(itemId => ServerSentEvent(itemId.value.toString))
                          .keepAlive(1.second, () => ServerSentEvent.heartbeat)
                      )
                      .provide(env)
                  }
                }
              }
            }
          } $endif$ $if(add_websocket_endpoint.truthy)$ ~

          pathPrefix("ws" / "items") {
            logRequestResult(("ws/items", InfoLevel)) {
              val greeterWebSocketService =
                Flow[Message].flatMapConcat {
                  case tm: TextMessage if tm.getStrictText == "deleted" =>
                    Source.futureSource(
                      unsafeRunToFuture(
                        ApplicationService.deletedEvents.toPublisher
                          .map(p =>
                            Source
                              .fromPublisher(p)
                              .map(itemId => TextMessage(s"deleted: \${itemId.value}"))
                          )
                          .provide(env)
                      )
                    )
                  case tm: TextMessage =>
                    Try(tm.getStrictText.toLong) match {
                      case Success(value) =>
                        Source.futureSource(
                          unsafeRunToFuture(
                            ApplicationService
                              .getItem(ItemId(value))
                              .bimap(
                                _.asThrowable,
                                o => Source(o.toList.map(i => TextMessage(i.toString)))
                              )
                              .provide(env)
                          )
                        )
                      case Failure(_) => Source.empty
                    }
                  case bm: BinaryMessage =>
                    bm.getStreamedData.runWith(Sink.ignore, env.get[ActorSystem])
                    Source.empty
                }

              handleWebSocketMessages(greeterWebSocketService)
            }
          }
       $endif$
    }
  )

  // accessors
  val routes: URIO[Api, Route] = ZIO.access[Api](a => Route.seal(a.get.routes))
}
