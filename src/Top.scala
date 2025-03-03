package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

object Config {
  def hasChipLink: Boolean = false
  def sdramUseAXI: Boolean = true
  val FPGAPlatform = true
}

// class ysyxSoCTop extends Module {
//   implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

//   val io = IO(new Bundle { })
//   val dut = LazyModule(new ysyxSoCASIC)
//   val mdut = Module(dut.module)
//   mdut.dontTouchPorts()
//   mdut.intr_from_chipSlave := DontCare
//   mdut.spi := DontCare
//   mdut.uart := DontCare
//   mdut.sdram := DontCare
//   mdut.gpio := DontCare
//   mdut.ps2 := DontCare
//   mdut.vga := DontCare
// }

object Elaborate extends App {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)
  val firtoolOptions = Array("--disable-annotation-unknown")
  val dut = LazyModule(new ysyxSoCASIC)
  circt.stage.ChiselStage.emitSystemVerilogFile(dut.module, args, firtoolOptions)
}
