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

class PS2Simulator extends Module {
  val io = IO(new Bundle {
    val buttons = Input(UInt(8.W))  // 4个物理按钮输入
    val ps2 = Flipped(new PS2IO)            // PS2接口输出
  })

  // 状态定义
  val sIdle :: sStart :: sData :: sParity :: sStop :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // 按钮状态跟踪
  val prevButtons = RegNext(io.buttons)
  val buttonEvents = (io.buttons ^ prevButtons).asTypeOf(io.buttons).asBools
  val keyPress = buttonEvents.zipWithIndex.map { case (e, i) => e && io.buttons(i) }
  val keyRelease = buttonEvents.zipWithIndex.map { case (e, i) => e && !io.buttons(i) }
  val keyReleased = RegNext(keyRelease.asUInt)

  // 扫描码配置
  val BREAK_CODE = 0xF0.U(8.W)
  val keyCodes = VecInit(
    0x1D.U(8.W),  // Button 0 -> w
    0x23.U(8.W),  // Button 1 -> d
    0x1B.U(8.W),  // Button 2 -> s
    0x1C.U(8.W),   // Button 3 -> a
    0x3C.U(8.W),   // u
    0x43.U(8.W),   // i
    0x3B.U(8.W),   // j
    0x42.U(8.W)    // k
  )

  // 扫描码队列（深度8）
  val scanQueue = Module(new Queue(UInt(8.W), 8))
  scanQueue.io.enq.valid := false.B
  scanQueue.io.enq.bits := DontCare
  scanQueue.io.deq.ready := false.B

  // 处理按键事件
  for (i <- 0 until keyCodes.length) {
    when(keyPress(i)) {
      scanQueue.io.enq.enq(keyCodes(i))  // 插入通码
    }
    when(keyRelease(i)) {
      scanQueue.io.enq.enq(BREAK_CODE)   // 插入断码前缀
    }
    when(keyReleased(i)){
      scanQueue.io.enq.enq(keyCodes(i))  // 插入通码
    }
  }

  // PS2协议引擎
  val clkDiv = RegInit(0.U(12.W))
  val ps2Clk = RegInit(true.B)
  val prevClk = RegNext(ps2Clk)
  val fallingEdge = !ps2Clk && prevClk
  val risingEdge = ps2Clk && !prevClk

  val dataReg = RegInit(0.U(11.W))
  val bitCount = RegInit(0.U(4.W))
  // val currentByte = RegInit(0.U(8.W))

  // 状态机转换
  switch(state) {
    is(sIdle) {
      when(scanQueue.io.deq.valid) {
        val currentByte = scanQueue.io.deq.bits
        val parity = !currentByte.xorR
        dataReg := Cat(1.B, parity, currentByte, 0.B) // 组装数据帧
        state := sStart
        bitCount := 0.U
        scanQueue.io.deq.ready := true.B
      }
    }
    is(sStart) {
      generateClock()
      when(risingEdge) {
        state := sData
        bitCount := bitCount + 1.U
        dataReg := dataReg >> 1
      }
    }
    is(sData) {
      generateClock()
      when(risingEdge) {
        dataReg := dataReg >> 1
        bitCount := bitCount + 1.U
        when(bitCount === 8.U) {
          state := sParity
        }
      }
    }
    is(sParity) {
      generateClock()
      when(risingEdge) {
        state := sStop
        dataReg := dataReg >> 1
        bitCount := bitCount + 1.U
      }
    }
    is(sStop) {
      generateClock()
      when(risingEdge) {
        state := sIdle
      }
    }
  }

  def generateClock(): Unit = {
    clkDiv := clkDiv + 1.U
    when(clkDiv === 500.U) {  // 生成10kHz时钟（100MHz/10000）
      ps2Clk := ~ps2Clk
      clkDiv := 0.U
    }
  }

  // 输出控制
  io.ps2.clk := Mux(state === sIdle, true.B, ps2Clk)
  // val fallingEdged = RegNext(fallingEdge)
  // val ps2_data = RegInit(true.B)
  // when (fallingEdged){
  //   ps2_data := dataReg(0)
  // }
  io.ps2.data := Mux(state === sIdle, true.B, dataReg(0))
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
    // val ps2_bundle = IO(new PS2IO)
    val buttons = IO(Input(UInt(8.W)))

    val mps2 = Module(new ps2Chisel)
    val PS2Simulator = Module(new PS2Simulator())

    mps2.io.clock := clock
    mps2.io.reset := reset
    mps2.io.in <> in
    PS2Simulator.io.buttons <> buttons
    PS2Simulator.io.ps2 <> mps2.io.ps2
  }
}
