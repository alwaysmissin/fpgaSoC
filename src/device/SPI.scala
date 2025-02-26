package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}

class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

object FlashXIP extends ChiselEnum{
  val IDLE = Value 
  val XIP_CMD, XIP_DIV, XIP_SS, XIP_CTRL, XIP_START, XIP_WAIT, XIP_READ = Value
}

class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    import FlashXIP._
    val (in, _) = node.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)
    mspi.io.clock := clock
    mspi.io.reset := reset

    val apb = WireDefault(in)
    val pready = WireDefault(mspi.io.in.pready)

    val state = RegInit(IDLE)
    val flashAddr = RegInit(0.U(32.W))
    switch(state){
      is (IDLE){
        // if paddr is in the range of the flash memory, enter XIP mode
        when(in.penable && in.psel && (in.paddr & 0x30000000.U) === 0x30000000.U){
          state := XIP_CMD
          // 低两位或许可以省略
          flashAddr := in.paddr(29, 0)
        }
      }
      is (XIP_CMD){
        apb.paddr := "h10001004".U // write command: 03h + addr
        apb.penable := true.B
        apb.pwdata := Cat(0x03.U(8.W), flashAddr(23, 0))
        apb.pstrb := 0xF.U
        apb.psel := true.B
        apb.pwrite := true.B
        pready := false.B
        when(apb.pready){
          state := XIP_DIV
        }
      }
      is (XIP_DIV){
        apb.paddr := "h10001014".U // write divider
        apb.penable := true.B
        apb.pwdata := 0x00000001.U // divider
        apb.psel := true.B
        apb.pstrb := 0xF.U
        apb.pwrite := true.B
        pready := false.B
        when(apb.pready){
          state := XIP_CTRL
        }

      }
      is (XIP_CTRL){
        apb.paddr := "h10001010".U // write control
        apb.penable := true.B
        apb.pwdata := 0x00001040.U // control: interrupt, 64bits
        apb.psel := true.B
        apb.pstrb := 0xF.U
        apb.pwrite := true.B
        pready := false.B
        when(apb.pready){
          state := XIP_START
        }
      }
      is (XIP_START){
        apb.paddr := "h10001010".U // write go_busy
        apb.pwdata := 0x00001140.U // go_busy
        apb.penable := true.B
        apb.psel := true.B
        apb.pstrb := 0xF.U
        apb.pwrite := true.B
        pready := false.B
        when(apb.pready){
          state := XIP_WAIT
        }
      }
      is (XIP_WAIT){
        pready := false.B
        when(mspi.io.spi_irq_out && apb.pready){ // when irq set
          state := XIP_READ
        }
      }
      is (XIP_READ){
        apb.paddr := "h10001000".U
        apb.penable := true.B
        apb.psel := true.B
        pready := apb.pready
        in.prdata := Cat(mspi.io.in.prdata(7, 0), mspi.io.in.prdata(15, 8), mspi.io.in.prdata(23, 16), mspi.io.in.prdata(31, 24))
        when(apb.pready){
          state := IDLE
        }
      }
    }
    // mspi.io.in <> in
    mspi.io.in <> apb
    in.pready := pready
    in.prdata := Mux(state === XIP_READ, 
                      Cat(mspi.io.in.prdata(7, 0), mspi.io.in.prdata(15, 8), mspi.io.in.prdata(23, 16), mspi.io.in.prdata(31, 24)), 
                      mspi.io.in.prdata)
    in.pslverr <> mspi.io.in.pslverr
    spi_bundle <> mspi.io.spi
    spi_bundle.ss := Mux(state === IDLE, mspi.io.spi.ss, 0xFE.U)
  }
}
