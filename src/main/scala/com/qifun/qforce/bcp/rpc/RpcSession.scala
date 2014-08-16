package com.qifun.qforce.bcp.rpc

import com.qifun.jsonStream.rpc.IJsonResponseHandler
import haxe.lang.Function
import com.qifun.qforce.bcp.BcpServer
import com.qifun.jsonStream.rpc.OutgoingProxy
import com.qifun.jsonStream.rpc.IncomingProxy
import scala.reflect.classTag
import scala.reflect.ClassTag
import com.qifun.jsonStream.JsonStream
import java.util.concurrent.atomic.AtomicInteger
import com.qifun.statelessFuture.util.Generator
import com.dongxiguo.continuation.utils.{ Generator => HaxeGenerator }
import java.nio.ByteBuffer
import scala.util.control.Exception.Catcher
import scala.collection.concurrent.TrieMap
import com.qifun.jsonStream.JsonStreamPair
import com.qifun.qforce.bcp.BcpSession
import com.qifun.jsonStream.rpc.IJsonService
import scala.runtime.BoxedUnit
import scala.reflect.macros.blackbox.Context

object RpcSession {

  final class OutgoingProxyEntry[Service](
    private[RpcSession] val serviceTag: ClassTag[Service],
    private[RpcSession] val outgoingView: IJsonService => Service)

  object OutgoingProxyEntry {

    import scala.language.implicitConversions
    import scala.language.experimental.macros

    def apply_impl[Service](c: Context)(serviceTag: c.Expr[ClassTag[Service]]): c.Expr[OutgoingProxyEntry[Service]] = {
      import c.universe._
      val Apply(TypeApply(_, Seq(serviceType)), _) = c.macroApplication
      val methodNameBuilder = new StringBuilder
      methodNameBuilder ++= "outgoingProxy"
      def buildMethodName(symbol: Symbol) {
        val owner = symbol.owner
        if (owner != NoSymbol) {
          buildMethodName(owner)
          methodNameBuilder += '_'
          methodNameBuilder ++= symbol.name.toString
        }
      }
      buildMethodName(serviceType.tpe.typeSymbol)
      val methodExpr = c.Expr(Ident(TermName(methodNameBuilder.toString)))
      reify {
        new _root_.com.qifun.qforce.bcp.rpc.RpcSession.OutgoingProxyEntry(serviceTag.splice, methodExpr.splice)
      }
    }

    implicit def apply[Service](
      implicit serviceTag: ClassTag[Service]): OutgoingProxyEntry[Service] = macro apply_impl[Service]

  }

  object IncomingProxyEntry {

    import scala.language.implicitConversions
    import scala.language.experimental.macros

    def apply_impl[Session, Service](
      c: Context)(
        rpcFactory: c.Expr[Session => Service])(
          serviceTag: c.Expr[ClassTag[Service]]): c.Expr[IncomingProxyEntry[Session, Service]] = {
      import c.universe._

      val Apply(Apply(TypeApply(_, Seq(_, serviceType)), _), _) = c.macroApplication
      val methodNameBuilder = new StringBuilder
      methodNameBuilder ++= "incomingProxy"

      def buildMethodName(symbol: Symbol) {
        val owner = symbol.owner
        if (owner != NoSymbol) {
          buildMethodName(owner)
          methodNameBuilder += '_'
          methodNameBuilder ++= symbol.name.toString
        }
      }
      buildMethodName(serviceType.tpe.typeSymbol)
      val methodExpr = c.Expr(Ident(TermName(methodNameBuilder.toString)))
      reify {
        new _root_.com.qifun.qforce.bcp.rpc.RpcSession.IncomingProxyEntry(
          rpcFactory.splice,
          serviceTag.splice,
          methodExpr.splice)
      }
    }

    implicit def apply[Session, Service](
      rpcFactory: Session => Service)(
        implicit serviceTag: ClassTag[Service]): IncomingProxyEntry[Session, Service] = macro apply_impl[Session, Service]

  }

  final class IncomingProxyEntry[Session, Service](
    private[RpcSession] val rpcFactory: Session => Service,
    private[RpcSession] val serviceTag: ClassTag[Service],
    private[RpcSession] val incomingView: Service => IJsonService)

  object IncomingProxyRegistration {

    private def incomingRpc[Session, Service](rpcFactory: Session => Service, incomingView: Service => IJsonService) = {
      { session: Session =>
        incomingView(rpcFactory(session))
      }
    }

    private def incomingRpc[Session, Service](entry: IncomingProxyEntry[Session, Service]): Session => IJsonService = {
      incomingRpc(entry.rpcFactory, entry.incomingView)
    }

    final def apply[Session](incomingEntries: IncomingProxyEntry[Session, _]*) = {
      val map = (for {
        entry <- incomingEntries
      } yield {
        entry.serviceTag.toString -> incomingRpc(entry)
      })(collection.breakOut(Map.canBuildFrom))
      new IncomingProxyRegistration(map)
    }

  }

  final class IncomingProxyRegistration[Session] private (
    private[RpcSession] val incomingProxyMap: Map[String, Session => IJsonService])
    extends AnyVal // Do not extends AnyVal because of https://issues.scala-lang.org/browse/SI-8702

  private final def generator1[Element](element: Element) = {
    new HaxeGenerator[Element](
      new haxe.lang.Function(2, 0) {
        override final def __hx_invoke2_o(
          argumentValue0: Double, argumentRef0: AnyRef,
          argumentValue1: Double, argumentRef1: AnyRef) = {
          val yieldFunction = argumentRef0.asInstanceOf[haxe.lang.Function]
          val returnFunction = argumentRef1.asInstanceOf[haxe.lang.Function]
          yieldFunction.__hx_invoke2_o(0, element, 0, returnFunction)
        }
      })
  }
}

trait RpcSession { _: BcpSession[_, _] =>

  import RpcSession.generator1

  protected def incomingServices: RpcSession.IncomingProxyRegistration[_ >: this.type]

  private val nextRequestId = new AtomicInteger(0)

  private val outgoingRpcResponseHandlers = TrieMap.empty[Int, IJsonResponseHandler]

  protected def toByteBuffer(js: JsonStream): Seq[ByteBuffer]

  protected def toJsonStream(buffers: java.nio.ByteBuffer*): JsonStream

  final def outgoingService[ServiceInterface](
    implicit entry: RpcSession.OutgoingProxyEntry[ServiceInterface]): ServiceInterface = {

    val serviceClassName = entry.serviceTag.toString

    entry.outgoingView(new IJsonService {
      override final def apply(request: JsonStream, handler: IJsonResponseHandler): Unit = {
        val requestId = nextRequestId.getAndIncrement()
        outgoingRpcResponseHandlers.putIfAbsent(requestId, handler) match {
          case None => {
            val requestStream = JsonStream.OBJECT(generator1(new JsonStreamPair(
              "request",
              JsonStream.OBJECT(generator1(new JsonStreamPair(
                requestId.toString,
                JsonStream.OBJECT(generator1(new JsonStreamPair(serviceClassName, request)))))))))
            send(toByteBuffer(requestStream): _*)
          }
          case Some(oldFunction) => {
            throw new IllegalStateException("")
          }
        }
      }
    })

  }

  override protected final def received(buffers: java.nio.ByteBuffer*): Unit = {
    toJsonStream(buffers: _*) match {
      case JsonStreamExtractor.Object(requestOrResponsePairs) => {
        for (requestOrResponsePair <- requestOrResponsePairs) {
          requestOrResponsePair.key match {
            case "request" => {
              requestOrResponsePair.value match {
                case JsonStreamExtractor.Object(idPairs) => {
                  for (idPair <- idPairs) {
                    val id = idPair.key
                    idPair.value match {
                      case JsonStreamExtractor.Object(servicePairs) => {
                        for (servicePair <- servicePairs) {
                          incomingServices.incomingProxyMap.get(servicePair.key) match {
                            case None => {
                              throw new RpcException.UnknownServiceName
                            }
                            case Some(incomingRpc) => {
                              incomingRpc(this).apply(
                                servicePair.value,
                                new IJsonResponseHandler {
                                  override final def onSuccess(responseBody: JsonStream): Unit = {
                                    val responseStream = JsonStream.OBJECT(generator1(new JsonStreamPair(
                                      "success",
                                      JsonStream.OBJECT(generator1(new JsonStreamPair(
                                        id,
                                        responseBody))))))
                                    send(toByteBuffer(responseStream): _*)
                                  }
                                  override final def onFailure(errorBody: JsonStream): Unit = {
                                    val responseStream = JsonStream.OBJECT(generator1(new JsonStreamPair(
                                      "failure",
                                      JsonStream.OBJECT(generator1(new JsonStreamPair(
                                        id,
                                        errorBody))))))
                                    send(toByteBuffer(responseStream): _*)
                                  }
                                })
                            }
                          }
                        }
                      }
                      case _ => {
                        throw new RpcException.IllegalRpcData
                      }
                    }
                  }
                }
                case _ => {
                  throw new RpcException.IllegalRpcData
                }
              }
            }
            case "failure" => {
              requestOrResponsePair.value match {
                case JsonStreamExtractor.Object(idPairs) => {
                  for (idPair <- idPairs) {
                    val id = try {
                      idPair.key.toInt
                    } catch {
                      case e: NumberFormatException => {
                        throw new RpcException.IllegalRpcData(cause = e)
                      }
                    }
                    outgoingRpcResponseHandlers.remove(id) match {
                      case None => {
                        throw new RpcException.IllegalRpcData
                      }
                      case Some(handler) => {
                        handler.onFailure(idPair.value)
                      }
                    }
                  }
                }
                case _ => {
                  throw new RpcException.IllegalRpcData
                }
              }

            }
            case "success" => {
              requestOrResponsePair.value match {
                case JsonStreamExtractor.Object(idPairs) => {
                  for (idPair <- idPairs) {
                    val id = try {
                      idPair.key.toInt
                    } catch {
                      case e: NumberFormatException => {
                        throw new RpcException.IllegalRpcData(cause = e)
                      }
                    }
                    outgoingRpcResponseHandlers.remove(id) match {
                      case None => {
                        throw new RpcException.IllegalRpcData
                      }
                      case Some(handler) => {
                        handler.onSuccess(idPair.value)
                      }
                    }
                  }
                }
                case _ => {
                  throw new RpcException.IllegalRpcData
                }
              }
            }
            case _ => {
              throw new RpcException.IllegalRpcData
            }
          }
        }
      }
      case _ => {
        throw new RpcException.IllegalRpcData
      }
    }
  }

} 