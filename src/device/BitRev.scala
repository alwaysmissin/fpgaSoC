package ysyx

import chisel3._
import chisel3.util._

class bitrev extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

object BitrevState extends ChiselEnum{
  val DATA_IN, DATA_OUT = Value
}

class bitrevChisel extends RawModule { // we do not need clock and reset
  import BitrevState._
  // val io = IO(Flipped(new SPIIO(1)))
  val io = IO(new Bundle{
    val sclk = Input(Clock())
    val ss = Input(AsyncReset())
    val mosi = Input(Bool())
    val miso = Output(Bool())
  })
  val state =   withClockAndReset(io.sclk, io.ss)(RegInit(DATA_IN))
  val data =    withClockAndReset(io.sclk, io.ss)(RegInit(0.U(8.W)))
  val counter = withClockAndReset(io.sclk, io.ss)(RegInit(0.U(8.W)))
  switch(state){
    is (DATA_IN) {
      data := Cat(data(6, 0), io.mosi)
    }
    is (DATA_OUT) {
      data := Cat(0.U(1.W), data(7, 1))
    }
  }

  switch(state) {
    is (DATA_IN) {
      when(counter === 7.U) {
        state := DATA_OUT
        counter := 0.U
      } otherwise {
        counter := counter + 1.U
      }
    }
    is (DATA_OUT) {
      counter := counter + 1.U
    }
  }

  io.miso := Mux(!io.ss.asBool && state === DATA_OUT, data(0), true.B)
}
