package multipaxos;

import static command.Command.CommandType.Del;
import static command.Command.CommandType.Get;
import static command.Command.CommandType.Put;
import static java.lang.Math.max;
import static log.Log.insert;
import static multipaxos.CommandType.DEL;
import static multipaxos.CommandType.GET;
import static multipaxos.CommandType.PUT;

import ch.qos.logback.classic.Logger;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import log.Log;
import org.slf4j.LoggerFactory;
import log.Instance;

class AcceptState {

  public long numRpcs;
  public long numOks;
  public final ReentrantLock mu;
  public final Condition cv;

  public AcceptState() {
    this.numRpcs = 0;
    this.numOks = 0;
    this.mu = new ReentrantLock();
    this.cv = mu.newCondition();
  }

}

class PrepareState {

  public long numRpcs;
  public long numOks;
  public long maxLastIndex;
  public HashMap<Long, log.Instance> log;
  public final ReentrantLock mu;
  public final Condition cv;


  public PrepareState() {
    this.numRpcs = 0;
    this.numOks = 0;
    this.maxLastIndex = 0;
    this.log = new HashMap<>();
    this.mu = new ReentrantLock();
    this.cv = mu.newCondition();
  }
}

class CommitState {

  public long numRpcs;
  public long numOks;
  public long minLastExecuted;
  public final ReentrantLock mu;
  public final Condition cv;

  public CommitState(long minLastExecuted) {
    this.numRpcs = 0;
    this.numOks = 0;
    this.minLastExecuted = minLastExecuted;
    this.mu = new ReentrantLock();
    this.cv = mu.newCondition();
  }
}

class RpcPeer {

  public final long id;
  public final ManagedChannel stub;

  public RpcPeer(long id, ManagedChannel stub) {
    this.id = id;
    this.stub = stub;
  }
}

public class MultiPaxos extends multipaxos.MultiPaxosRPCGrpc.MultiPaxosRPCImplBase {

  protected static final long kIdBits = 0xff;
  protected static final long kRoundIncrement = kIdBits + 1;
  protected static final long kMaxNumPeers = 0xf;
  private static final Logger logger = (Logger) LoggerFactory.getLogger(MultiPaxos.class);
  private AtomicLong ballot;
  private final Log log;
  private final long id;
  private final AtomicBoolean commitReceived;
  private final long commitInterval;
  private final int port;
  private final int numPeers;
  private final List<RpcPeer> rpcPeers;
  private final ReentrantLock mu;
  private final ExecutorService threadPool;

  private final Condition cvLeader;
  private final Condition cvFollower;

  private Server rpcServer;
  private boolean rpcServerRunning;
  private final Condition rpcServerRunningCv;
  private final ExecutorService rpcServerThread;

  private final AtomicBoolean prepareThreadRunning;
  private final ExecutorService prepareThread;

  private final AtomicBoolean commitThreadRunning;
  private final ExecutorService commitThread;

  public MultiPaxos(Log log, Configuration config) {
    this.ballot = new AtomicLong(kMaxNumPeers);
    this.log = log;
    this.id = config.getId();
    commitReceived = new AtomicBoolean(false);
    this.commitInterval = config.getCommitInterval();

    String target = config.getPeers().get((int) this.id);
    this.port = Integer.parseInt(target.substring(target.indexOf(":") + 1));
    this.numPeers = config.getPeers().size();

    threadPool = Executors.newFixedThreadPool(config.getThreadPoolSize());
    rpcServerRunning = false;
    prepareThreadRunning = new AtomicBoolean(false);
    commitThreadRunning = new AtomicBoolean(false);

    mu = new ReentrantLock();
    cvLeader = mu.newCondition();
    cvFollower = mu.newCondition();
    prepareThread = Executors.newSingleThreadExecutor();
    commitThread = Executors.newSingleThreadExecutor();
    rpcServerThread = Executors.newSingleThreadExecutor();
    rpcServerRunningCv = mu.newCondition();

    rpcPeers = new ArrayList<>();
    long rpcId = 0;
    for (var peer : config.getPeers()) {
      ManagedChannel channel = ManagedChannelBuilder.forTarget(peer).usePlaintext().build();
      rpcPeers.add(new RpcPeer(rpcId++, channel));
    }
  }

  public void start() {
    startPrepareThread();
    startCommitThread();
    startRPCServer();
  }

  public void stop() {
    stopRPCServer();
    stopPrepareThread();
    stopCommitThread();

    threadPool.shutdown();
    try {
      threadPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void startRPCServer() {
    rpcServer = ServerBuilder.forPort(port).addService(this).build();
    try {
      rpcServer.start();
      logger.debug(id + " starting rpc server at " + rpcServer.getPort());
      mu.lock();
      rpcServerRunning = true;
      rpcServerRunningCv.signal();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      mu.unlock();
    }
    rpcServerThread.submit(this::blockUntilShutDown);
  }

  public void stopRPCServer() {
    try {
      mu.lock();
      while (!rpcServerRunning) {
        rpcServerRunningCv.await();
      }
      mu.unlock();
      logger.debug(id + " stopping rpc at " + rpcServer.getPort());
      rpcServer.shutdown().awaitTermination(30, TimeUnit.SECONDS);
      for (var peer : rpcPeers) {
        peer.stub.shutdown();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    rpcServerThread.shutdown();
  }

  public void startPrepareThread() {
    logger.debug(id + " starting prepare thread");
    assert (!prepareThreadRunning.get());
    prepareThreadRunning.set(true);
    prepareThread.submit(this::prepareThread);
  }

  public void stopPrepareThread() {
    logger.debug(id + " stopping prepare thread");
    assert (prepareThreadRunning.get());
    mu.lock();
    prepareThreadRunning.set(false);
    cvFollower.signal();
    mu.unlock();
    prepareThread.shutdown();
    try {
      prepareThread.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void startCommitThread() {
    logger.debug(id + " starting commit thread");
    assert (!commitThreadRunning.get());
    commitThreadRunning.set(true);
    commitThread.submit(this::commitThread);
  }

  public void stopCommitThread() {
    logger.debug(id + " stopping commit thread");
    assert (commitThreadRunning.get());
    mu.lock();
    commitThreadRunning.set(false);
    cvLeader.signal();
    mu.unlock();
    commitThread.shutdown();
    try {
      commitThread.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public Result replicate(command.Command command, long clientId) {
    var ballot = ballot();
    if (isLeader(ballot, this.id)) {
      return runAcceptPhase(ballot, log.advanceLastIndex(), command, clientId);

    }
    if (isSomeoneElseLeader(ballot, this.id)) {
      return new Result(MultiPaxosResultType.kSomeoneElseLeader, extractLeaderId(ballot));
    }
    return new Result(MultiPaxosResultType.kRetry, null);
  }

  void prepareThread() {
    while (prepareThreadRunning.get()) {
      mu.lock();
      try {
        while (prepareThreadRunning.get() && isLeader(this.ballot.get(), this.id)) {
          cvFollower.await();
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      } finally {
        mu.unlock();
      }
      while (prepareThreadRunning.get()) {
        sleepForRandomInterval();
        if (receivedCommit()) {
          continue;
        }
        var nextBallot = nextBallot();
        var r = runPreparePhase(nextBallot);
        if (r != null) {
          var maxLastIndex = r.getKey();
          var log = r.getValue();
          becomeLeader(nextBallot, maxLastIndex);
          replay(nextBallot, log);
          break;
        }
      }
    }
  }

  public void commitThread() {
    while (commitThreadRunning.get()) {
      mu.lock();
      try {
        while (commitThreadRunning.get() && !isLeader(this.ballot.get(), this.id)) {
          cvLeader.await();
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      } finally {
        mu.unlock();
      }
      var gle = log.getGlobalLastExecuted();
      while (commitThreadRunning.get()) {
        var ballot = ballot();
        if (!isLeader(ballot, this.id)) {
          break;
        }
        gle = runCommitPhase(ballot, gle);
        sleepForCommitInterval();
      }
    }
  }

  public Map.Entry<Long, HashMap<Long, log.Instance>> runPreparePhase(long ballot) {
    var state = new PrepareState();
    multipaxos.PrepareRequest.Builder request = multipaxos.PrepareRequest.newBuilder();
    request.setSender(this.id);
    request.setBallot(ballot);

    if (ballot > this.ballot.get()) {
      state.numRpcs++;
      state.numOks++;
      state.log = log.getLog();
      state.maxLastIndex = log.getLastIndex();
    } else {
      return null;
    }

    for (var peer : rpcPeers) {
      if (peer.id == this.id) {
        continue;
      }
      threadPool.submit(() -> {
        multipaxos.PrepareResponse response;
        try {
          response = multipaxos.MultiPaxosRPCGrpc.newBlockingStub(peer.stub)
              .prepare(request.build());
          logger.debug(id + " sent prepare request to " + peer.id);
        } catch (StatusRuntimeException e) {
          logger.debug(id + " RPC connection failed to " + peer.id);
          state.mu.lock();
          state.numRpcs++;
          state.cv.signal();
          state.mu.unlock();
          return;
        }
        state.mu.lock();
        ++state.numRpcs;
        if (response.getType() == multipaxos.ResponseType.OK) {
          ++state.numOks;
          for (int i = 0; i < response.getInstancesCount(); ++i) {
            state.maxLastIndex = max(state.maxLastIndex, response.getInstances(i).getIndex());
            insert(state.log, makeInstance(response.getInstances(i)));
          }
        } else {
          becomeFollower(response.getBallot());
        }
        state.cv.signal();
        state.mu.unlock();
      });
    }
    state.mu.lock();
    while (state.numOks <= numPeers / 2 && state.numRpcs != numPeers) {
      try {
        state.cv.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    if (state.numOks > numPeers / 2) {
      state.mu.unlock();
      return new HashMap.SimpleEntry<>(state.maxLastIndex, state.log);
    }
    state.mu.unlock();
    return null;
  }

  public Result runAcceptPhase(long ballot, long index, command.Command command, long clientId) {
    var state = new AcceptState();
    log.Instance instance = new log.Instance();

    instance.setBallot(ballot);
    instance.setIndex(index);
    instance.setClientId(clientId);
    instance.setState(Instance.InstanceState.kInProgress);
    instance.setCommand(command);

    if (ballot == this.ballot.get()) {
      state.numRpcs++;
      state.numOks++;
      log.append(instance);
    } else {
      var leader = extractLeaderId(this.ballot.get());
      return new Result(MultiPaxosResultType.kSomeoneElseLeader, leader);
    }

    multipaxos.AcceptRequest.Builder request = multipaxos.AcceptRequest.newBuilder();
    request.setSender(this.id);
    request.setInstance(makeProtoInstance(instance));

    for (var peer : rpcPeers) {
      if (peer.id == this.id) {
        continue;
      }
      threadPool.submit(() -> {
        multipaxos.AcceptResponse response;
        try {
          response = multipaxos.MultiPaxosRPCGrpc.newBlockingStub(peer.stub)
              .accept(request.build());
        } catch (StatusRuntimeException e) {
          logger.debug(id + " RPC connection failed to " + peer.id);
          state.mu.lock();
          ++state.numRpcs;
          state.cv.signal();
          state.mu.unlock();
          return;
        }
        logger.debug(id + " sent accept request to " + peer.id);
        state.mu.lock();
        ++state.numRpcs;
        if (response.getType() == multipaxos.ResponseType.OK) {
          ++state.numOks;
        } else {
          becomeFollower(response.getBallot());
        }
        state.cv.signal();
        state.mu.unlock();
      });
    }
    state.mu.lock();
    while (isLeader(this.ballot.get(), id) && state.numOks <= numPeers / 2
            && state.numRpcs != numPeers) {
      try {
        state.cv.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    if (state.numOks > numPeers / 2) {
      log.commit(index);
      state.mu.unlock();
      return new Result(MultiPaxosResultType.kOk, null);
    }
    state.mu.unlock();
    if (!isLeader(this.ballot.get(), id)) {
      return new Result(MultiPaxosResultType.kSomeoneElseLeader, extractLeaderId(this.ballot.get()));
    }
    return new Result(MultiPaxosResultType.kRetry, null);
  }

  public Long runCommitPhase(long ballot, long globalLastExecuted) {
    var state = new CommitState(log.getLastExecuted());
    multipaxos.CommitRequest.Builder request = multipaxos.CommitRequest.newBuilder();

    request.setBallot(ballot);
    request.setSender(this.id);
    request.setLastExecuted(state.minLastExecuted);
    request.setGlobalLastExecuted(globalLastExecuted);

    state.numRpcs++;
    state.numOks++;
    state.minLastExecuted = log.getLastExecuted();
    log.trimUntil(globalLastExecuted);

    for (var peer : rpcPeers) {
      if (peer.id == this.id) {
        continue;
      }
      threadPool.submit(() -> {
        multipaxos.CommitResponse response;
        try {
          response = multipaxos.MultiPaxosRPCGrpc.newBlockingStub(peer.stub)
              .commit(request.build());
          logger.debug(id + " sent commit to " + peer.id);
        } catch (StatusRuntimeException e) {
          logger.debug(id + " RPC connection failed to " + peer.id);
          state.mu.lock();
          state.numRpcs++;
          state.cv.signal();
          state.mu.unlock();
          return;
        }
        state.mu.lock();
        ++state.numRpcs;
        if (response.isInitialized()) {
          if (response.getType() == multipaxos.ResponseType.OK) {
            ++state.numOks;

            if (response.getLastExecuted() < state.minLastExecuted) {
              state.minLastExecuted = response.getLastExecuted();
            }
          } else {
            becomeFollower(response.getBallot());
          }
        }
        state.cv.signal();
        state.mu.unlock();
      });
    }
    state.mu.lock();
    while (isLeader(this.ballot.get(), id) && state.numRpcs != numPeers) {
      try {
        state.cv.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    if (state.numOks == numPeers) {
      state.mu.unlock();
      return state.minLastExecuted;
    }
    state.mu.unlock();
    return globalLastExecuted;
  }

  public void replay(long ballot, HashMap<Long, log.Instance> log) {

    for (Map.Entry<Long, log.Instance> entry : log.entrySet()) {
      var r = runAcceptPhase(ballot, entry.getValue().getIndex(), entry.getValue().getCommand(),
          entry.getValue().getClientId());
      while (r.type == MultiPaxosResultType.kRetry) {
        r = runAcceptPhase(ballot, entry.getValue().getIndex(), entry.getValue().getCommand(),
            entry.getValue().getClientId());
      }
      if (r.type == MultiPaxosResultType.kSomeoneElseLeader) {
        return;
      }
    }
  }

  @Override
  public void prepare(multipaxos.PrepareRequest request, StreamObserver<multipaxos.PrepareResponse> responseObserver) {
    logger.debug(id + " <-- prepare-- " + request.getSender());
    multipaxos.PrepareResponse.Builder responseBuilder = multipaxos.PrepareResponse.newBuilder();
    if (request.getBallot() > ballot.get()) {
      becomeFollower(request.getBallot());
      for (var i : log.instances()) {
        responseBuilder.addInstances(makeProtoInstance(i));
      }
      responseBuilder = responseBuilder.setType(multipaxos.ResponseType.OK);
    } else {
      responseBuilder = responseBuilder.setBallot(ballot.get()).setType(multipaxos.ResponseType.REJECT);
    }
    var response = responseBuilder.build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void accept(multipaxos.AcceptRequest request, StreamObserver<multipaxos.AcceptResponse> responseObserver) {
    logger.debug(this.id + " <--accept---  " + request.getSender());
    multipaxos.AcceptResponse.Builder responseBuilder = multipaxos.AcceptResponse.newBuilder();
    if (request.getInstance().getBallot() >= this.ballot.get()) {
      log.append(makeInstance(request.getInstance()));
      responseBuilder = responseBuilder.setType(multipaxos.ResponseType.OK);
      if (request.getInstance().getBallot() > this.ballot.get()) {
        becomeFollower(request.getInstance().getBallot());
      }
    }
    if (request.getInstance().getBallot() < this.ballot.get()) {
      responseBuilder = responseBuilder.setBallot(ballot.get()).setType(multipaxos.ResponseType.REJECT);
    }
    var response = responseBuilder.build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  public void commit(multipaxos.CommitRequest request,
                     StreamObserver<multipaxos.CommitResponse> responseObserver) {
    logger.debug(id + " <--commit--- " + request.getSender());
    var response = multipaxos.CommitResponse.newBuilder();
    if (request.getBallot() >= ballot.get()) {
      commitReceived.set(true);
      log.commitUntil(request.getLastExecuted(), request.getBallot());
      log.trimUntil(request.getGlobalLastExecuted());
      response.setLastExecuted(log.getLastExecuted());
      response.setType(multipaxos.ResponseType.OK);
      if (request.getBallot() > ballot.get()) {
        becomeFollower(request.getBallot());
      }
    } else {
      response.setBallot(this.ballot.get());
      response.setType(multipaxos.ResponseType.REJECT);
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  public static long extractLeaderId(long ballot) {
    return ballot & kIdBits;
  }

  public static boolean isLeader(long ballot, long id) {
    return extractLeaderId(ballot) == id;
  }

  public static boolean isSomeoneElseLeader(long ballot, long id) {
    return !isLeader(ballot, id) && extractLeaderId(ballot) < kMaxNumPeers;
  }

  public long getId() {
    return id;
  }

  public long ballot() {
    return ballot.get();
  }

  public long nextBallot() {
    long nextBallot = ballot.get();
    nextBallot += kRoundIncrement;
    nextBallot = (nextBallot & ~kIdBits) | id;
    return nextBallot;
  }

  public void becomeLeader(long newBallot, long newLastIndex) {
    mu.lock();
    try {
      logger.debug(id + " became a leader: ballot: " + ballot + " -> " + newBallot);
      ballot.set(newBallot);
      log.setLastIndex(newLastIndex);
      cvLeader.signal();
    } finally {
      mu.unlock();
    }
  }

  public void becomeFollower(long newBallot) {
    mu.lock();
    try {
      if (newBallot <= ballot.get()) {
        return;
      }
      var oldLeaderId = extractLeaderId(ballot.get());
      var newLeaderId = extractLeaderId(newBallot);
      if (newLeaderId != id && (oldLeaderId == id || oldLeaderId == kMaxNumPeers)) {
        logger.debug(id + " became a follower: ballot: " + ballot + " -> " + newBallot);
        cvFollower.signal();
      }
      ballot.set(newBallot);
    } finally {
      mu.unlock();
    }
  }

  public void sleepForCommitInterval() {
    try {
      Thread.sleep(commitInterval);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public void sleepForRandomInterval() {
    Random random = new Random();
    var sleepTime = random.nextInt(0, (int) commitInterval / 2);
    try {
      Thread.sleep(commitInterval + commitInterval / 2 + sleepTime);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public boolean receivedCommit() {
    return commitReceived.compareAndExchange(true, false);
  }

  public static multipaxos.Command makeProtoCommand(command.Command command) {
    multipaxos.CommandType commandType = null;
    switch (command.getCommandType()) {
      case Del -> commandType = DEL;
      case Get -> commandType = GET;
      case Put -> commandType = PUT;
    }
    return multipaxos.Command.newBuilder().setType(commandType).setKey(command.getKey())
        .setValue(command.getValue()).build();
  }

  public static multipaxos.Instance makeProtoInstance(log.Instance inst) {
    multipaxos.InstanceState state = null;
    switch (inst.getState()) {
      case kInProgress -> state = multipaxos.InstanceState.INPROGRESS;
      case kCommitted -> state = multipaxos.InstanceState.COMMITTED;
      case kExecuted -> state = multipaxos.InstanceState.EXECUTED;
    }

    return multipaxos.Instance.newBuilder().setBallot(inst.getBallot()).setIndex(inst.getIndex())
        .setClientId(inst.getClientId()).setState(state)
        .setCommand(makeProtoCommand(inst.getCommand())).build();
  }

  public static command.Command makeCommand(multipaxos.Command cmd) {
    command.Command command = new command.Command();
    command.Command.CommandType commandType = null;
    switch (cmd.getType()) {
      case DEL -> commandType = Del;
      case GET -> commandType = Get;
      case PUT -> commandType = Put;
      case UNRECOGNIZED -> {
      }
    }
    command.setCommandType(commandType);
    command.setKey(cmd.getKey());
    command.setValue(cmd.getValue());
    return command;
  }

  public static log.Instance makeInstance(multipaxos.Instance inst) {
    log.Instance instance = new log.Instance();
    instance.setBallot(inst.getBallot());
    instance.setIndex(inst.getIndex());
    instance.setClientId(inst.getClientId());
    instance.setCommand(makeCommand(inst.getCommand()));
    return instance;
  }

  private void blockUntilShutDown() {
    if (rpcServer != null) {
      try {
        rpcServer.awaitTermination();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

}

