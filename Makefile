# AegisMesh (Java). Needs a JDK >= 21. Tests need JUnit 5 (apt: junit5, or any junit-platform-console-standalone jar).
JAVAC      ?= javac
JAVA       ?= java
JUNIT      ?= /usr/share/java/junit-platform-console-standalone.jar
JACOCO_DIR ?= /usr/share/java
OUT        := out
MAIN_SRC   := $(shell find src/main -name '*.java')
TEST_SRC   := $(shell find src/test -name '*.java')
JAR        := $(OUT)/aegismesh.jar
COVER_MIN  ?= 85
LINT       := -Xlint:all -Werror

.PHONY: all build lint test e2e cov check bench demo clean

all: build

$(OUT)/classes/.stamp: $(MAIN_SRC)
	rm -rf $(OUT)/classes && mkdir -p $(OUT)/classes
	$(JAVAC) $(LINT) -d $(OUT)/classes $(MAIN_SRC)
	touch $@

$(JAR): $(OUT)/classes/.stamp
	jar --create --file $@ --main-class aegismesh.app.Main -C $(OUT)/classes .

build: $(JAR)    ## compile (warnings are errors) and package out/aegismesh.jar

$(OUT)/test-classes/.stamp: $(OUT)/classes/.stamp $(TEST_SRC)
	rm -rf $(OUT)/test-classes && mkdir -p $(OUT)/test-classes
	$(JAVAC) $(LINT) -cp $(OUT)/classes:$(JUNIT) -d $(OUT)/test-classes $(TEST_SRC)
	touch $@

lint: $(OUT)/test-classes/.stamp   ## same as compiling main + tests with -Xlint:all -Werror

test: $(OUT)/test-classes/.stamp   ## unit + in-process integration tests (excludes the process-level e2e tests)
	$(JAVA) -jar $(JUNIT) execute --class-path $(OUT)/classes:$(OUT)/test-classes \
	  --scan-class-path $(OUT)/test-classes --exclude-tag e2e --fail-if-no-tests \
	  --details=summary --disable-banner

e2e: $(JAR) $(OUT)/test-classes/.stamp   ## real processes: 3 gateways + backends, failover, SIGTERM, flags
	$(JAVA) -Daegis.jar=$(abspath $(JAR)) -jar $(JUNIT) execute --class-path $(OUT)/classes:$(OUT)/test-classes \
	  --scan-class-path $(OUT)/test-classes --include-tag e2e --fail-if-no-tests --details=summary --disable-banner

cov: $(OUT)/test-classes/.stamp   ## unit tests under JaCoCo with a coverage gate (COVER_MIN, default 85 %)
	rm -f $(OUT)/jacoco.exec
	mkdir -p $(OUT)/tools && unzip -p $(JACOCO_DIR)/org.jacoco.agent.jar jacocoagent.jar > $(OUT)/tools/jacocoagent.jar
	$(JAVA) -javaagent:$(OUT)/tools/jacocoagent.jar=destfile=$(OUT)/jacoco.exec,includes=aegismesh.* \
	  -jar $(JUNIT) execute --class-path $(OUT)/classes:$(OUT)/test-classes --scan-class-path $(OUT)/test-classes \
	  --exclude-tag e2e --fail-if-no-tests --details=none --disable-banner
	$(JAVAC) -cp "$(JACOCO_DIR)/*" -d $(OUT)/tools tools/CoverageReport.java
	$(JAVA) -cp "$(OUT)/tools:$(JACOCO_DIR)/*" CoverageReport $(OUT)/jacoco.exec $(OUT)/classes $(COVER_MIN)

check: build lint test cov e2e demo   ## everything CI runs

bench: build   ## full benchmark (about 10 minutes); BENCH_ARGS="-scale 0.3 -reps 1" for a quick run
	$(JAVA) -jar $(JAR) bench $(BENCH_ARGS)

demo: build   ## 3 gateways + 3 backends, a slow backend, a leader kill
	bash scripts/demo.sh

clean:
	rm -rf $(OUT) results
