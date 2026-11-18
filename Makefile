JAR=target/cgsense-0.1.0-SNAPSHOT-jar-with-dependencies.jar

DISCOVER_ARGS=discover --limit 2
MINE_ARGS=mine-pairs
TEST_ARGS=tester --output output/results
ARGS=$(MINE_ARGS)


build:
	mvn clean package

run: $(JAR)
	java -jar $(JAR) $(ARGS)

$(JAR):
	mvn clean package

clean:
	mvn clean
