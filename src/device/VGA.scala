package ysyx

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import ysyx.SDPRAM_SYNC
import ysyx.Config.FPGAPlatform

class VGAIO extends Bundle {
  val r = if(FPGAPlatform) Output(UInt(4.W)) else Output(UInt(8.W))
  val g = if(FPGAPlatform) Output(UInt(4.W)) else Output(UInt(8.W))
  val b = if(FPGAPlatform) Output(UInt(4.W)) else Output(UInt(8.W))
  val hsync = Output(Bool())
  val vsync = Output(Bool())
  val valid = Output(Bool())
}

class VGACtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val vga = new VGAIO
}

class vga_top_apb extends BlackBox {
  val io = IO(new VGACtrlIO)
}

class vga_ctrl extends BlackBox {
  val io = IO(new Bundle {
    val pclk = Input(Clock())
    val reset = Input(Bool())
    val vga_data = Input(UInt(12.W)) // rgb4444
    val h_addr = Output(UInt(10.W))
    val v_addr = Output(UInt(10.W))
    val hsync = Output(Bool())
    val vsync = Output(Bool())
    val valid = Output(Bool())
    val vga_r = Output(UInt(4.W))
    val vga_g = Output(UInt(4.W))
    val vga_b = Output(UInt(4.W))
  })
}

class vgaChisel extends RawModule {
  val io = IO(new VGACtrlIO)
  withClockAndReset(io.clock, io.reset){

    val vga_ctrl = Module(new vga_ctrl())
    vga_ctrl.io.pclk <> io.clock
    vga_ctrl.io.reset <> io.reset
    // val g_memory = Mem(0x7FFFF, UInt(24.W))
    // val g_memory = SDPRAM_SYNC(0x7FFFF, UInt(24.W))
    // 640*480
    val g_memory = Module(new SDPRAM_SYNC(0x1FFFF, UInt(12.W)))
    // loadMemoryFromFileInline(g_memory, "/home/jiunian/Program/ysyx-workbench/nvboard/example/resource/test.hex")

    // write to gpu memory
    val pready = RegInit(false.B)
    io.in.pslverr := false.B
    io.in.pready <> pready
    io.in.prdata := 0.U(32.W)

    val wen = WireDefault(false.B)
    // g_memory.io.wen   := wen
    // g_memory.io.waddr := io.in.paddr(22, 2)
    // g_memory.io.wdata := io.in.pwdata(23, 0).asTypeOf(g_memory.io.wdata)
    // g_memory.io.wstrobe := 1.U
    val wdata = Cat((io.in.pwdata(31, 24) >> 4)(3, 0), 
                    (io.in.pwdata(23, 16) >> 4)(3, 0), 
                    (io.in.pwdata(15, 8 ) >> 4)(3, 0), 
                    (io.in.pwdata(7 , 0 ) >> 4)(3, 0))
    g_memory.write(io.in.psel && io.in.pwrite, io.in.paddr(22, 2), wdata(11, 0).asTypeOf(g_memory.io.wdata))
    when (io.in.psel) {
      pready := true.B
      when (io.in.pwrite){
        wen := true.B
      }
    } otherwise {
      pready := false.B
    }

    // output for display
    // vga_ctrl.io.vga_data := g_memory.read(Cat(vga_ctrl.io.h_addr, vga_ctrl.io.v_addr(8, 0)))
    vga_ctrl.io.vga_data := g_memory.read((vga_ctrl.io.h_addr >> 1) + (vga_ctrl.io.v_addr >> 1) * 320.U).head
    io.vga.hsync <> vga_ctrl.io.hsync
    io.vga.vsync <> vga_ctrl.io.vsync
    io.vga.valid <> vga_ctrl.io.valid
    io.vga.r <> {if(FPGAPlatform) vga_ctrl.io.vga_r else Cat(vga_ctrl.io.vga_r, 0.U(4.W))}
    io.vga.g <> {if(FPGAPlatform) vga_ctrl.io.vga_g else Cat(vga_ctrl.io.vga_g, 0.U(4.W))}
    io.vga.b <> {if(FPGAPlatform) vga_ctrl.io.vga_b else Cat(vga_ctrl.io.vga_b, 0.U(4.W))}
  }
}

class APBVGA(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val vga_bundle = IO(new VGAIO)

    val mvga = Module(new vgaChisel)
    mvga.io.clock := clock
    mvga.io.reset := reset
    mvga.io.in <> in
    vga_bundle <> mvga.io.vga
  }
}
