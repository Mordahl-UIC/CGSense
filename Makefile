JAR=target/cgsense-0.1.0-SNAPSHOT-jar-with-dependencies.jar

DISCOVER_ARGS=discover --limit 3
MINE_ARGS=mine-pairs
TEST_ARGS=callgraph-pair --output output/
ARGS=$(TEST_ARGS)


build:
	mvn clean package

run: $(JAR)
	java -jar $(JAR) $(ARGS)

$(JAR):
	mvn clean package

clean:
	mvn clean
