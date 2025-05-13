package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

object Config {
  def hasChipLink: Boolean = false
  def sdramUseAXI: Boolean = true
  def nrInterrupt: Int = 5
  var FPGAPlatform = true
}

class ysyxSoCTop extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val io = IO(new Bundle { })
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
  mdut.externalPins := DontCare
}

object Elaborate extends App {
  import ysyx.Config._
  if (args.length > 0 && args(0) == "fpga") {
    print("Generate for FPGA\n")
  } else {
    FPGAPlatform = false
    print("Generate for Simulation\n")
  }
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)
  val firtoolOptions = Array("--disable-annotation-unknown")
  if (FPGAPlatform){
    val dut = LazyModule(new ysyxSoCASIC)
    circt.stage.ChiselStage.emitSystemVerilogFile(dut.module, args, firtoolOptions)
  } else {
    circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
  }
}
