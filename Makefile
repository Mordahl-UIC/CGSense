JAR=target/cgsense-0.1.0-SNAPSHOT-jar-with-dependencies.jar
ARGS=discover --limit 3 --criteria repo-criteria/TestCriteria1.json --output discovered-repos/Test1.jsonl

build:
	mvn clean package

run: $(JAR)
	java -jar $(JAR) $(ARGS)

$(JAR):
	mvn clean package

clean:
	mvn clean
