package olx

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, Route}
import olx.scrap.{Scrapper, StateActor}
import olx.{Config}
import reactivemongo.api.AsyncDriver
import spray.json.{DefaultJsonProtocol, PrettyPrinter}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn

object Server
    extends App
    with SprayJsonSupport
    with Directives
    with DefaultJsonProtocol {

  implicit val globalState: ActorSystem[StateActor.Request] =
    akka.actor.typed.ActorSystem(StateActor(), "stateActor")
  implicit val executionContext: ExecutionContextExecutor =
    concurrent.ExecutionContext.global
  implicit val printer: PrettyPrinter.type = PrettyPrinter
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  val mongoDriver: AsyncDriver = AsyncDriver()

  val route: Route =
    pathSingleSlash {
      get {
        val html = scala.io.Source
          .fromInputStream(getClass.getResourceAsStream("/html/index.html"))
          .mkString
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))
      }
    } ~
      path("download") {
        post {
          parameters("olxUrl".as[String], "max".as[Int], "collection")
            .as(Order.apply _) { order =>
              complete(Scrapper.createDownloadStream(order, mongoDriver))
            } ~
            entity(as[Order]) { order =>
              complete(scrap.Scrapper.createDownloadStream(order, mongoDriver))
            } ~
//            formFields("olxUrl", "max".actorSystem[Int], "url".optional, "database".optional, "collection".optional).actorSystem(Order.apply) {
            formFields("olxUrl", "max".as[Int], "collection")
              .as(Order.apply _) { order =>
                complete(
                  scrap.Scrapper.createDownloadStream(order, mongoDriver)
                )

              }
        }
      }

  val bindingFuture: Future[Http.ServerBinding] =
    Http().newServerAt(Config.ipAddress, Config.port).bindFlow(route)

  println(
    s"Сервер запущен http://172.31.16.228:3031/\nНажмите RETURN чтобы прекратить работу..."
  )
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .flatMap(_ => mongoDriver.close())
    .onComplete(_ => globalState.terminate())

}
