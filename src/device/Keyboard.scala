package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class PS2IO extends Bundle {
  val clk = Input(Bool())
  val data = Input(Bool())
}

class PS2CtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val ps2 = new PS2IO
}

class ps2_top_apb extends BlackBox {
  val io = IO(new PS2CtrlIO)
}

class ps2_keyboard extends BlackBox {
  val io = IO(new Bundle{
    val clk = Input(Clock())
    val clrn = Input(Bool())
    val ps2_clk = Input(Bool())
    val ps2_data = Input(Bool())
    val data = Output(UInt(8.W))
    val ready = Output(Bool())
    val nextdata_n = Input(Bool())
    val overflow = Output(Bool())
  })
}

class ps2Chisel extends Module {
  val io = IO(new PS2CtrlIO)
  val ps2 = Module(new ps2_keyboard())
  ps2.io.clrn := !io.reset
  ps2.io.clk <> io.clock
  ps2.io.ps2_clk <> io.ps2.clk
  ps2.io.ps2_data <> io.ps2.data
  val nextdata_n = WireDefault(true.B)
  val prdata = RegInit(0.U(8.W))
  val pready = RegInit(false.B)
  io.in.pslverr := false.B
  io.in.pready <> pready
  io.in.prdata <> prdata
  ps2.io.nextdata_n <> nextdata_n
  when (io.in.psel && (io.in.paddr & 0xF.U) === 0.U) {
    pready := true.B
    when (ps2.io.ready) {
      nextdata_n := io.in.psel && io.in.pready
      prdata := ps2.io.data
    } otherwise {
      prdata := 0.U
    }
  } otherwise {
    pready := false.B
  }

}

class APBKeyboard(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val ps2_bundle = IO(new PS2IO)

    val mps2 = Module(new ps2Chisel)
    mps2.io.clock := clock
    mps2.io.reset := reset
    mps2.io.in <> in
    ps2_bundle <> mps2.io.ps2
  }
}
