package scalapb.zio_grpc

import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import scalapb.zio_grpc.helloworld.testservice._
import zio._
import java.time

object ServerStreamingBenchmarkApp extends App {

  val size = 100000L

  val server =
    ServerLayer.access[ZioTestservice.Greeter](ServerBuilder.forPort(50051))

  val client =
    ZLayer.fromManaged {
      for {
        ss     <- ZManaged.service[Server.Service]
        port   <- ss.port.toManaged_
        ch      = ManagedChannelBuilder.forAddress("localhost", 50051).usePlaintext()
        client <- ZioTestservice.GreeterClient.managed(ZManagedChannel(ch)).orDie
      } yield client
    }

  val service =
    ZLayer.succeed[ZioTestservice.Greeter] {
      new GreeterImpl(size)
    }

  def run(args: List[String]) = ZIO
    .foreach(Array(8192, 65536)) { queueSize =>
      val props = java.lang.System.getProperties();
      props.setProperty("zio-grpc.backpressure-queue-size", queueSize.toString());

      for {
        _      <- console.putStrLn(s"Starting with queue size $queueSize")
        cpt    <- Ref.make(0)
        start  <- clock.instant.flatMap(Ref.make(_))
        result <- ZioTestservice.GreeterClient
                    .sayHelloStreaming(HelloRequest(request = Some(Hello(name = "Testing streaming"))))
                    .tap(_ => cpt.update(_ + 1))
                    .tap { _ =>
                      for {
                        now     <- clock.instant
                        started <- start.get
                        _       <- ZIO.when(time.Duration.between(started, now).toSeconds() >= 10)(
                                     start.set(now) *> cpt.get.flatMap(cpt => console.putStrLn(s"Received $cpt messages"))
                                   )
                      } yield ()
                    }
                    .runDrain
                    .timed
        _      <- console.putStrLn(s"queue size: $queueSize (${result._1.toMillis()}ms)")
      } yield ()
    }
    .provideSomeLayer[clock.Clock with console.Console](service >+> server >+> client)
    .exitCode

}
