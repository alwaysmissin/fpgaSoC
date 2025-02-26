package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog
import chisel3.util.BitPat.bitPatToUInt
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO extends Bundle {
  val clk = Output(Bool()) // 时钟信号
  val cke = Output(Bool()) // 时钟使能信号
  val cs  = Output(Bool()) // 命令信号cs
  val ras = Output(Bool()) // 命令信号ras
  val cas = Output(Bool()) // 命令信号cas
  val we  = Output(Bool()) // 命令信号we
  val a   = Output(UInt(13.W)) // 地址
  val ba  = Output(UInt(2.W))  // 存储体地址
  val dqm = Output(UInt(4.W))  // 数据掩码
  val dq  = Analog(32.W)   // 数据
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

object SDRAM_STATE extends ChiselEnum{
  val SDRAM_IDLE, SDRAM_ACTIVE, SDRAM_READ, SDRAM_WRITE, SDRAM_CAS = Value
}

object SDRAM_CMD {
  def CMD_ACTIVE = BitPat("b0011")
  def CMD_READ   = BitPat("b0101")
  def CMD_WRITE  = BitPat("b0100")
  def CMD_LOAD   = BitPat("b0000")
}

class SDRAM_ACTIVE_HELPER extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle{
    val ba = Input(UInt(2.W))
    val a  = Input(UInt(13.W))
    val enable = Input(Bool())
  })
  setInline("sdram_active_helper.v",
    """module SDRAM_ACTIVE_HELPER(
      |  input [1 :0] ba,
      |  input [12:0] a,
      |  input enable
      |);
      |import "DPI-C" function void sdram_active(input bit[1 :0] ba, input bit[12 :0] a);
      |always @(*) begin
      |  if (enable) sdram_active(ba, a);
      |end
      |endmodule
  """.stripMargin
  )
}

class SDRAM_READ_HELPER extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle{
    val ba = Input(UInt(2.W))
    val a  = Input(UInt(13.W))
    val enable = Input(Bool())
    val rdata = Output(UInt(32.W))
  })
  setInline("sdram_read_helper.v",
    """
      |module SDRAM_READ_HELPER(
      |  input [1 :0] ba,
      |  input [12:0] a,
      |  input enable,
      |  output reg [31:0] rdata
      |);
      |import "DPI-C" function void sdram_read(input bit[1 :0] ba, input bit[12 :0] a, output int rdata);
      |always @(*) begin
      |  if (enable) sdram_read(ba, a, rdata);
      |  else rdata = 32'b0;
      |end
      |endmodule
      |""".stripMargin
  )
}

class SDRAM_WRITE_HELPER extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle{
    val ba = Input(UInt(2.W))
    val a  = Input(UInt(13.W))
    val wdata = Input(UInt(32.W))
    val dqm = Input(UInt(4.W))
    val enable = Input(Bool())
  })
  setInline("sdram_write_helper.v",
    """
      |module SDRAM_WRITE_HELPER(
      |  input [1 :0] ba,
      |  input [12:0] a,
      |  input [31:0] wdata,
      |  input [3 :0] dqm,
      |  input enable
      |);
      |import "DPI-C" function void sdram_write(input bit[1 :0] ba, input bit[12 :0] a, input int wdata, input bit[3 :0] dqm);
      |always @(*) begin
      |  if (enable) sdram_write(ba, a, wdata, dqm);
      |end
      |endmodule
      |""".stripMargin
  )
}

class sdramChisel extends RawModule {
  import SDRAM_STATE._
  import SDRAM_CMD._
  val io = IO(Flipped(new SDRAMIO))
  withClockAndReset((!io.clk).asClock, (!io.cke).asAsyncReset){

    val modeReg = RegInit(0.U(13.W))
    val burstLengthField = modeReg(2, 0)
    val burstType = modeReg(3)
    val casLength = modeReg(6, 4)
    val opMode = modeReg(8, 7)
    val writeBurstMode = modeReg(9)

    val burstLength = MuxCase(0.U, Seq(
      (burstLengthField === 0.U) -> 1.U,
      (burstLengthField === 1.U) -> 2.U,
      (burstLengthField === 2.U) -> 4.U,
      (burstLengthField === 3.U) -> 8.U
    ))

    val cmd = Cat(io.cs, io.ras, io.cas, io.we)
    val state = RegInit(SDRAM_IDLE)
    val casCounter = RegInit(0.U(3.W))
    val burstCounter = RegInit(0.U(3.W))

    val a_r = RegInit(0.U(13.W))
    val ba_r = RegInit(0.U(2.W))

    val activeHelper = Module(new SDRAM_ACTIVE_HELPER())
    val activeHelperEnable = WireDefault(false.B)
    activeHelper.io.enable <> activeHelperEnable
    activeHelper.io.a      <> io.a
    activeHelper.io.ba     <> io.ba

    val readHelper = Module(new SDRAM_READ_HELPER())
    val readHelperEnable = WireDefault(false.B)
    val writeHelper = Module(new SDRAM_WRITE_HELPER())
    val writeHelperEnable = WireDefault(false.B)

    val dqout = WireDefault(0.U(32.W))
    val dqin = TriStateInBuf(io.dq, dqout, readHelperEnable)

    readHelper.io.enable <> readHelperEnable
    readHelper.io.a      := a_r + burstCounter
    readHelper.io.ba     <> ba_r
    dqout := readHelper.io.rdata

    writeHelper.io.enable <> writeHelperEnable
    writeHelper.io.a      := Mux(state === SDRAM_IDLE, io.a, a_r + burstCounter)
    writeHelper.io.ba     <> io.ba
    writeHelper.io.wdata  <> dqin
    writeHelper.io.dqm    <> io.dqm


    switch(state){
      is (SDRAM_IDLE){
        switch (cmd){
          is (bitPatToUInt(CMD_LOAD)){
            modeReg := io.a
          }
          is (bitPatToUInt(CMD_ACTIVE)){
            activeHelperEnable := true.B
          }
          is (bitPatToUInt(CMD_READ)){
            a_r := io.a
            ba_r := io.ba
            state := SDRAM_READ
            casCounter := Mux(casCounter === casLength - 1.U, 0.U, casCounter + 1.U)
          }
          is (bitPatToUInt(CMD_WRITE)){
            a_r := io.a
            ba_r := io.ba
            state := Mux(burstCounter === burstLength - 1.U, SDRAM_IDLE, SDRAM_WRITE)
            burstCounter := Mux(burstCounter === burstLength - 1.U, 0.U, burstCounter + 1.U)
            writeHelperEnable := true.B
          }
        }
      }
      is (SDRAM_CAS){
        casCounter := Mux(casCounter === casLength - 1.U, 0.U, casCounter + 1.U)
        state := Mux(casCounter === casLength - 1.U, SDRAM_READ, SDRAM_CAS)
      }
      is (SDRAM_READ){
        burstCounter := Mux(burstCounter === burstLength - 1.U, 0.U, burstCounter + 1.U)
        state := Mux(burstCounter === burstLength - 1.U, SDRAM_IDLE, SDRAM_READ)
        readHelperEnable := true.B
      }
      is (SDRAM_WRITE){
        burstCounter := Mux(burstCounter === burstLength - 1.U, 0.U, burstCounter + 1.U)
        state := Mux(burstCounter === burstLength - 1.U, SDRAM_IDLE, SDRAM_WRITE)
        writeHelperEnable := true.B
      }
    }
  }
}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = address,
      executable    = true,
      supportsWrite = TransferSizes(1, beatBytes),
      supportsRead  = TransferSizes(1, beatBytes),
      interleavedId = Some(0))
    ),
    beatBytes  = beatBytes)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}
