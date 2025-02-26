package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4DelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
  val out = new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4))
}

class axi4_delayer extends BlackBox {
  val io = IO(new AXI4DelayerIO)
}



class AXI4DelayerChisel(freqRatio: Int = 1, scalingFactor: Int = 0) extends Module {
  object AXI4DelayerState extends ChiselEnum {
    val IDLE, WAIT, DELAY, RESP = Value
  }
  val io = IO(new AXI4DelayerIO)
  val rWaitCounter = RegInit(0.U(32.W))
  val rDelayCounter = RegInit(0.U(32.W))
  val wWaitCounter = RegInit(0.U(32.W))
  val wDelayCounter = RegInit(0.U(32.W))
  val rRespQ = Queue.irrevocable(io.out.r, 8, flow = true)
  val bResp = RegInit(io.out.b.bits)

  val rCounterW = Wire(Irrevocable(UInt(32.W)))
  val rDelayCounterQ = Queue.irrevocable(rCounterW, 8, flow = true)
  rDelayCounterQ.ready := false.B
  rCounterW.valid := io.out.r.valid
  rCounterW.bits := 0.U

  val rChannelBusy = RegInit(false.B)
  val wChannelBusy = RegInit(false.B)
  when (io.in.ar.fire){
    rChannelBusy := true.B
  }.elsewhen(io.in.r.fire && io.in.r.bits.last){
    rChannelBusy := false.B
  }
  when (io.in.aw.fire && io.in.w.fire){
    wChannelBusy := true.B
  }.elsewhen(io.in.b.fire){
    wChannelBusy := false.B
  }

  val rState = RegInit(AXI4DelayerState.IDLE)
  switch(rState){
    is (AXI4DelayerState.IDLE){
      when (io.in.ar.valid){
        rState := AXI4DelayerState.WAIT
      }
    }
    is (AXI4DelayerState.WAIT){
      rWaitCounter := rWaitCounter + 1.U
      // when rDelayCounter is 0, try to get a number from rDelayCounterQ
      rDelayCounter := Mux(rDelayCounter === 0.U,
        Mux(rDelayCounterQ.valid, rDelayCounterQ.bits.asUInt - rWaitCounter, 0.U),
        rDelayCounter - 1.U)
      // when data in rDelayCounterQ is available and rDelayCounterQ is 0, get the data from rDelayCounterQ
      rDelayCounterQ.ready := rDelayCounter === 0.U
      when (io.out.r.valid){
        rCounterW.bits := (rWaitCounter * freqRatio.U >> scalingFactor.U)
        rState := Mux(io.out.r.bits.last, AXI4DelayerState.DELAY, AXI4DelayerState.WAIT)
      }
    }
    is (AXI4DelayerState.DELAY){
      rWaitCounter := rWaitCounter + 1.U
      // when rDelayCounter is 0, try to get a number from rDelayCounterQ
      rDelayCounter := Mux(rDelayCounter === 0.U,
        Mux(rDelayCounterQ.valid, rDelayCounterQ.bits.asUInt - rWaitCounter, 0.U),
        rDelayCounter - 1.U)
      // when data in rDelayCounterQ is available and rDelayCounterQ is 0, get the data from rDelayCounterQ
      rDelayCounterQ.ready := rDelayCounter === 0.U
      when (rDelayCounter === 0.U && !rDelayCounterQ.valid && rRespQ.bits.last){
        rState := AXI4DelayerState.IDLE
        rDelayCounter := 0.U
        rWaitCounter := 0.U
      }
    }
  }

  val wState = RegInit(AXI4DelayerState.IDLE)
  switch(wState){
    is (AXI4DelayerState.IDLE){
      when (io.in.aw.valid || io.in.w.valid){
        wState := AXI4DelayerState.WAIT
      }
    }
    is (AXI4DelayerState.WAIT){
      wWaitCounter := wWaitCounter + 1.U
      when (io.out.b.valid){
        wState := AXI4DelayerState.DELAY
        wDelayCounter := (wWaitCounter * freqRatio.U >> scalingFactor.U) - wWaitCounter
        bResp := io.out.b.bits
      }
    }
    is (AXI4DelayerState.DELAY){
      wDelayCounter := wDelayCounter - 1.U
      when (wDelayCounter === 0.U){
        wState := AXI4DelayerState.RESP
        wWaitCounter := 0.U
        wDelayCounter := 0.U
      }
    }
    is (AXI4DelayerState.RESP){
      wState := AXI4DelayerState.IDLE
    }
  }

  if (freqRatio <= 1){
    io.out <> io.in
  } else {
    {
      io.out.ar <> io.in.ar
      io.in.ar.ready := io.out.ar.ready && !rChannelBusy
      io.out.ar.valid := io.in.ar.valid && !rChannelBusy
      io.out.r.ready := io.out.r.valid
      io.in.r.valid := rDelayCounter === 0.U && rRespQ.valid && !io.out.r.valid
      rRespQ.ready := io.in.r.fire
      io.in.r.bits := rRespQ.bits
    }
    {
      io.out.aw <> io.in.aw
      io.out.w  <> io.in.w
      io.in.aw.ready := io.out.aw.ready && !wChannelBusy
      io.out.aw.valid := io.in.aw.valid && !wChannelBusy
      io.in.w.ready := io.out.w.ready && !wChannelBusy
      io.out.w.valid := io.in.w.valid && !wChannelBusy
      io.out.b.ready := io.out.b.valid && wState === AXI4DelayerState.WAIT
      io.in.b.valid := wState === AXI4DelayerState.RESP
      io.in.b.bits := bResp
    }
  }
}

class AXI4DelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val delayer = Module(new AXI4DelayerChisel(freqRatio = 9, scalingFactor = 1))
      delayer.io.clock := clock
      delayer.io.reset := reset
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object AXI4Delayer {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4delay = LazyModule(new AXI4DelayerWrapper)
    axi4delay.node
  }
}
