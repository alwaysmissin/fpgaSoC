package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class APBDelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val out = new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
}

class apb_delayer extends BlackBox {
  val io = IO(new APBDelayerIO)
}

object APBDelayerState extends ChiselEnum {
  val IDLE, WAIT, DELAY, RESP = Value
}

/**
 * APB Delayer
 * @param freqRatio the ratio after scaled by scalingFactor
 * @param scalingFactor scalingFactor, it refers to how many bits to shift
 */
class APBDelayerChisel(freqRatio: Int = 1, scalingFactor: Int = 0) extends Module {
  import APBDelayerState._
  val io = IO(new APBDelayerIO)
  //  io.out <> io.in
  val state = RegInit(IDLE)
  val apbResppslverrReg = RegInit(false.B)
  val apbRespprdataReg = RegInit(0.U.asTypeOf(io.out.prdata))
  val waitCounter = RegInit(0.U(32.W))
  val delayDecounter = RegInit(0.U(32.W))
  switch(state) {
    is (IDLE) {
      when (io.in.psel) {
        state := WAIT
      }
    }
    is (WAIT) {
      waitCounter := waitCounter + 1.U
      when (io.out.pready){
        state := DELAY
        apbResppslverrReg := io.out.pslverr
        apbRespprdataReg := io.out.prdata
        delayDecounter := (waitCounter * (freqRatio.U) >> scalingFactor.U) - waitCounter
      }
    }
    is (DELAY) {
      delayDecounter := delayDecounter - 1.U
      when (delayDecounter === 0.U) {
        state := RESP
        waitCounter := 0.U
        delayDecounter := 0.U
      }
    }
    is (RESP) {
      state := IDLE
      apbResppslverrReg := false.B
      apbRespprdataReg := 0.U.asTypeOf(io.out.prdata)
    }
  }
  if (freqRatio <= 1) {
    io.in <> io.out
  } else {
    io.in <> io.out
    io.out.psel := io.in.psel && (state === IDLE || state === WAIT)
    io.out.penable := io.in.penable && (state === IDLE || state === WAIT)
    io.out.pwrite := io.in.pwrite && (state === IDLE || state === WAIT)
    io.in.pready := state === RESP
    io.in.pslverr := apbResppslverrReg
    io.in.prdata := apbRespprdataReg
  }
}

class APBDelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = APBIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val delayer = Module(new APBDelayerChisel(freqRatio = 9, scalingFactor = 1))
      delayer.io.clock := clock
      delayer.io.reset := reset
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object APBDelayer {
  def apply()(implicit p: Parameters): APBNode = {
    val apbdelay = LazyModule(new APBDelayerWrapper)
    apbdelay.node
  }
}
