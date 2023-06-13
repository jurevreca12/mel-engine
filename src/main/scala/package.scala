import scala.language.implicitConversions
import chisel3._
import chisel3.util._
import afe.bus._

package object afe {
  implicit def axistreamToDriver[T <: Data](x: AXIStreamIO[T]): AXIStreamDriver[T] = new AXIStreamDriver(x)
}
