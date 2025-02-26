package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck = Output(Bool())
  val ce_n = Output(Bool())
  val dio = Analog(4.W)
}

class psram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi = new QSPIIO
  })
}

class psram extends BlackBox {
  val io = IO(Flipped(new QSPIIO))
}

object PSRAM_STATE extends ChiselEnum{
  val CMD, ADDR, WRITE, WAIT, READ, ERROR = Value
}

object PSRAM_MODE extends ChiselEnum{
  val QSPI, QPI = Value
}

class PSRAM_READ_HELPER extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle {
    val raddr = Input(UInt(32.W))
    val ren = Input(Bool())
    val rdata = Output(UInt(32.W))
  })
  setInline("psram_read_helper.v",
    """module PSRAM_READ_HELPER(
      |  input [31:0] raddr,
      |  input ren,
      |  output reg [31:0] rdata
      |);
      |import "DPI-C" function void psram_read(input int raddr, output int rdata);
      |always @(*) begin
      |  if (ren) psram_read(raddr, rdata);
      |  else rdata = 0;
      |end
      |endmodule
    """.stripMargin
  )
}

class PSRAM_WRITE_HELPER extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val waddr = Input(UInt(32.W))
    val wen = Input(Bool())
    val wlen = Input(UInt(8.W))
    val wdata = Input(UInt(32.W))
  })
  setInline("psram_write_helper.v",
    """module PSRAM_WRITE_HELPER(
      |  input clk,
      |  input [31:0] waddr,
      |  input wen,
      |  input [7:0]  wlen,
      |  input [31:0] wdata
      |);
      |import "DPI-C" function void psram_write(input int waddr, input byte wlen, input int wdata);
      |always @(*) begin
      |  if (wen) psram_write(waddr, wlen, wdata);
      |end
      |endmodule
    """.stripMargin
  )
}

class psramChisel extends RawModule {
  import PSRAM_STATE._
  import PSRAM_MODE._
  val io = IO(Flipped(new QSPIIO))
  
  withClockAndReset(io.sck.asClock, io.ce_n.asAsyncReset){
    val mode = RegInit(QPI)
    val state = RegInit(CMD)
    val counter = RegInit(0.U(8.W))
    val cmd = RegInit(0.U(8.W))
    val addr = RegInit(0.U(24.W))
    val wdata = RegInit(0.U(32.W))

    val dout = WireDefault(0.U(4.W))
    val di = TriStateInBuf(io.dio, dout, state === READ) // change this if you need

    // do psram read
    val psram_reader = Module(new PSRAM_READ_HELPER())
    psram_reader.io.raddr := addr
    psram_reader.io.ren   := state === WAIT && counter === 0.U
    val rdata = RegEnable(psram_reader.io.rdata, psram_reader.io.ren)
    val rdata_swap = Cat(
                rdata(27, 24), rdata(31, 28), 
                rdata(19, 16), rdata(23, 20), 
                rdata(11, 8) , rdata(15, 12), 
                rdata(3, 0)  , rdata(7, 4))
      // val rdata = RegInit(0.U(32.W))

    // do psram write
    val do_write = RegInit(false.B)
    val wlen = RegInit(0.U(8.W))
    val psram_writer = Module(new PSRAM_WRITE_HELPER())
    psram_writer.io.clk := io.sck.asClock
    psram_writer.io.wen := (state === CMD && do_write) || (io.ce_n && state === WRITE)
    psram_writer.io.waddr := addr
    psram_writer.io.wlen := wlen
    psram_writer.io.wdata := MuxCase(0.U, Seq(
      (wlen === 1.U) -> Cat(0.U(24.W), wdata(7, 0)),
      (wlen === 2.U) -> Cat(0.U(16.W), wdata(7, 0), wdata(15, 8)),
      (wlen === 4.U) -> Cat(wdata(7, 0), wdata(15, 8), wdata(23, 16), wdata(31, 24))
    ))
    
    
      // when(state === CMD && do_write){
      //   // TODO: do write here
      // }
    when(state === CMD && cmd === 0x35.U){
      switch(mode){
        is (QSPI){ mode := QPI }
        is (QPI) { mode := QSPI}
      }
    }

    switch(state){
      is (CMD) {
        state := Mux(counter === Mux(mode === QSPI, 7.U, 1.U), Mux(cmd === 0x35.U, CMD, ADDR), CMD)
        wlen := 0.U
        do_write := false.B
      }
      is (ADDR) {
        state := Mux(counter === 5.U, 
                      Mux(cmd === 0xeb.U, WAIT, Mux(cmd === 0x38.U, WRITE, ERROR)),
                      ADDR)
      }
      is (WRITE) {
        do_write := true.B
        state := Mux(counter === 7.U, CMD, WRITE)
        when(counter === 1.U || counter === 3.U || counter === 5.U || counter === 7.U){
          wlen := wlen + 1.U
        }
      }
      is (WAIT) {
        state := Mux(counter === 6.U, READ, WAIT)
      }
      is (READ) {
        state := Mux(counter === 7.U, CMD, READ)
      }
      is (ERROR) {
        printf("Error: state = %x, cmd = %x, addr = %x, data = %x\n", (state === ERROR).asUInt, cmd, addr, wdata)
      }
    }

    switch(state){
      is (CMD) {
        counter := Mux(counter === Mux(mode === QSPI, 7.U, 1.U), 0.U, counter + 1.U)
      }
      is (ADDR) {
        counter := Mux(counter === 5.U, 0.U, counter + 1.U)
      }
      is (WRITE) {
        counter := Mux(counter === 7.U, 0.U, counter + 1.U)
      }
      is (WAIT) {
        counter := Mux(counter === 6.U, 0.U, counter + 1.U)
      }
      is (READ) {
        counter := Mux(counter === 7.U, 0.U, counter + 1.U)
      }
      is (ERROR) {
        counter := 0.U
      }
    }


    when(state === CMD){
      cmd := Mux(mode === QSPI, Cat(cmd(6, 0), di(0)), Cat(cmd(3, 0), di))
    }
    when(state === ADDR){
      addr := Cat(addr(19, 0), di)
    }
    when(state === WRITE){
      wdata := Cat(wdata(27, 0), di)
    }
    when(state === READ){
      dout := Mux(counter === 0.U, rdata_swap(3, 0), rdata(3, 0))
      rdata := Mux(counter === 0.U, Cat(0.U, rdata_swap(31, 4)), Cat(0.U, rdata(31, 4)))
    }
  }
  // withClockAndReset((~io.sck.asBool).asClock, io.ce_n){
  //   when(state === READ){
  //     dout := rdata(3, 0)
  //     rdata := Cat(0.U, rdata(31, 4)) 
  //   }
  // }
}

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(new psram_top_apb)
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
