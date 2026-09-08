JAR=target/cgsense-0.1.0-SNAPSHOT-jar-with-dependencies.jar
ARGS=

build:
	mvn clean package

run: $(JAR)
	java -jar $(JAR) $(ARGS)

$(JAR):
	mvn clean package

clean:
	mvn clean
