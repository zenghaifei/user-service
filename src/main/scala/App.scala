import actors.{EmailCodeEntity, UserInfoEntity, UserTokenEntity}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import com.github.swagger.akka.SwaggerSite
import projections.UsersManagerProjection
import routes.{AuthRouter, UserRouter}
import services.{JwtService, MessagesService}

object App extends SwaggerSite {

  def main(args: Array[String]): Unit = {
    ActorSystem(Behaviors.setup[String] { context =>
      implicit val system = context.system
      import context.executionContext
      val config = context.system.settings.config

      val sharding = ClusterSharding(system)
      UserTokenEntity.shardRegion(sharding)
      UserInfoEntity.shardRegion(sharding)
      EmailCodeEntity.shardRegion(sharding)

      val jwtService = new JwtService(config)
      val authRoute = new AuthRouter(jwtService).routes
      val messagesService = new MessagesService(config)
      val userRoute = new UserRouter(messagesService).routes
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

