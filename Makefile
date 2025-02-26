V_FILE_GEN   = build/ysyxSoCTop.sv
V_FILE_FINAL = build/ysyxSoCFull.v
SCALA_FILES = $(shell find src/ -name "*.scala")
MILL_VERSION = 0.11.8
PERIP_PATH   = perip

$(V_FILE_FINAL): $(SCALA_FILES)
	MILL_VERSION=$(MILL_VERSION) mill -i ysyxsoc.runMain ysyx.Elaborate --target-dir $(@D)
	mv $(V_FILE_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i -e 's/ysyx_00000000/ysyx_23060051/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

verilog: $(V_FILE_FINAL)

npc: verilog
	cp $(V_FILE_FINAL) $(NPC_HOME)/vsrc/ysyxSoCFull.v
	cp -r $(PERIP_PATH) $(NPC_HOME)/vsrc

clean:
	-rm -rf build/

dev-init:
	git submodule update --init --recursive
	cd rocket-chip && git apply ../patch/rocket-chip.patch

.PHONY: verilog clean dev-init
