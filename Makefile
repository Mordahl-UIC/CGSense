JAR=target/cgsense-0.1.0-SNAPSHOT-jar-with-dependencies.jar

DISCOVER_ARGS=discover --limit 2 --output output/discovered-repos/repos.jsonl
MINE_ARGS=mine-pairs --output output/commit-pairs/pairs.jsonl --repos output/discovered-repos/repos.jsonl
ARGS=$(MINE_ARGS)


build:
	mvn clean package

run: $(JAR)
	java -jar $(JAR) $(ARGS)

$(JAR):
	mvn clean package

clean:
	mvn clean
