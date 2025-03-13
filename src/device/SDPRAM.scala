package ysyx

import chisel3._
import chisel3.util.log2Ceil
import ysyx.Config.FPGAPlatform
import ysyx.xpm_memory_sdpram
import chisel3.util.experimental.loadMemoryFromFileInline

class SDPRAM_SYNC[T <: Data](size: Int, t: T, lineSize: Int = 1) extends Module{
  override val desiredName = s"SDPRAM_SYNC_${size}_${t.getWidth}_${lineSize}"
  val addrBits = log2Ceil(size)
  val io = IO(new Bundle{
    val raddr = Input(UInt(addrBits.W))
    val rdata = Output(Vec(lineSize, t))
    val wen = Input(Bool())
    val waddr = Input(UInt(addrBits.W))
    val wstrobe = Input(UInt(lineSize.W))
    val wdata = Input(Vec(lineSize, t))
  })
  if (FPGAPlatform) {
    val split = !(lineSize == 1 || t.getWidth == 8 || t.getWidth == 9)
    val mems = Seq.fill(if (split) lineSize else 1) {
      Module(new xpm_memory_sdpram(log2Ceil(size), if (split) t.getWidth else t.getWidth * lineSize, t.getWidth))
    }

    mems.zipWithIndex.foreach({case(mem, idx) => 
      mem.io.clka := clock.asBool
      mem.io.clkb := clock.asBool
      mem.io.ena  := io.wen
      mem.io.enb  := true.B
      mem.io.addra := io.waddr
      mem.io.addrb := io.raddr
      mem.io.wea   := (if (split) io.wstrobe(idx) else io.wstrobe)
      if (split) {
        mem.io.dina := io.wdata(idx).asUInt
        io.rdata(idx) := mem.io.doutb.asTypeOf(t)
      }
      mem.io.regceb := true.B
      mem.io.rstb := false.B
      mem.io.sleep := false.B
      mem.io.injectdbiterra := false.B
      mem.io.injectsbiterra := false.B
    })
    if (!split) {
      mems.head.io.dina := io.wdata.asUInt
      io.rdata := mems.head.io.doutb.asTypeOf(Vec(lineSize, t))
    }

  } else {
    val mem = SyncReadMem(size, Vec(lineSize, t))
    io.rdata := mem.read(io.raddr)
    when (io.wen) {
      if (lineSize == 1){
        mem.write(io.waddr, io.wdata)
      } else {
        mem.write(io.waddr, io.wdata, io.wstrobe.asBools)
      }
    }
  }
  def read(addr: UInt): Vec[T] = {
    io.raddr := addr
    io.rdata // 实际需要根据SyncReadMem特性处理同步延迟
  }

  def write(cond: Bool, addr: UInt, data: Vec[T], mask: UInt = "b1".U): Unit = {
    io.wen := cond
    io.waddr := addr
    io.wdata := data
    io.wstrobe := mask
  }
}

class SDPRAM_ASYNC[T <: Data](size: Int, t: T, lineSize: Int = 1) extends Module{
  val addrBits = log2Ceil(size)
  val io = IO(new Bundle{
    val raddr = Input(UInt(addrBits.W))
    val rdata = Output(Vec(lineSize, t))
    val wen = Input(Bool())
    val waddr = Input(UInt(addrBits.W))
    val wstrobe = Input(UInt(lineSize.W))
    val wdata = Input(Vec(lineSize, t))
  })
  if (FPGAPlatform) {
    val split = !(lineSize == 1 || t.getWidth == 8 || t.getWidth == 9)
    val mems = Seq.fill(if (split) lineSize else 1) {
      Module(new xpm_memory_sdpram(log2Ceil(size), if (split) t.getWidth else t.getWidth * lineSize, t.getWidth, READ_LATENCY = 0))
    }

    mems.zipWithIndex.foreach({case(mem, idx) => 
      mem.io.clka := clock.asBool
      mem.io.clkb := clock.asBool
      mem.io.ena  := io.wen
      mem.io.enb  := true.B
      mem.io.addra := io.waddr
      mem.io.addrb := io.raddr
      mem.io.wea   := (if (split) io.wstrobe(idx) else io.wstrobe)
      if (split) {
        mem.io.dina := io.wdata(idx).asUInt
        io.rdata(idx) := mem.io.doutb.asTypeOf(t)
      }
      mem.io.regceb := true.B
      mem.io.rstb := false.B
      mem.io.sleep := false.B
      mem.io.injectdbiterra := false.B
      mem.io.injectsbiterra := false.B
    })
    if (!split) {
      mems.head.io.dina := io.wdata.asUInt
      io.rdata := mems.head.io.doutb.asTypeOf(Vec(lineSize, t))
    }

  } else {
    val mem = SyncReadMem(size, Vec(lineSize, t))
    io.rdata := mem.read(io.raddr)
    when (io.wen) {
      if (lineSize == 1){
        mem.write(io.waddr, io.wdata)
      } else {
        mem.write(io.waddr, io.wdata, io.wstrobe.asBools)
      }
    }
  }
}
