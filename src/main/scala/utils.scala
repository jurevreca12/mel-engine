package melengine

package object utils {
  def toBinary(i: Int, digits: Int = 8): String =
    String.format(s"%${digits}s", i.toBinaryString.takeRight(digits)).replace(' ', '0')
  def toBinaryB(i: BigInt, digits: Int = 8): String = String.format("%" + digits + "s", i.toString(2)).replace(' ', '0')
}
