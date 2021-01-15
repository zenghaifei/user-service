import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import com.github.swagger.akka.SwaggerSite
import projections.UsersManagerProjection
import routes.{AuthRouter, UserRouter}
import services.JwtService

object App extends SwaggerSite {

  def main(args: Array[String]): Unit = {
    ActorSystem(Behaviors.setup[String] { context =>
      implicit val system = context.system
      import context.executionContext
      val config = context.system.settings.config

      val jwtService = new JwtService(config)
      val authRoute = new AuthRouter(jwtService).routes
      val userRoute = new UserRouter().routes
      val routes = concat(authRoute, userRoute)
      val host = "0.0.0.0"
      val port = config.getInt("server.port")
      Http().newServerAt(host, port).bind(routes)

      UsersManagerProjection.init(system)
      context.log.info(s"server started at ${host}:${port}")
      Behaviors.same
    }, "user-service")
  }
}

