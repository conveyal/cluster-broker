Run the broker:

`$ mvn exec:java -Dexec.mainClass="com.conveyal.qbroker.QBrokerMain"`

Enqueue some work:

`$ watch -n 1 curl -X POST -d [1,2,3,4,5,6,7,8,9] http://localhost:9001/job/userid/graphA/jobid`

Long-poll to dequeue some work:

`$ watch -n 4 curl --max-time 3 -v -X GET http://localhost:9001/job/userid/graphA/jobid`

Run a few more long-poll loops with different graph affinities in other terminals:

`$ watch -n 4 curl --max-time 3 -v -X GET http://localhost:9001/job/userid/BBBBB/jobid`
