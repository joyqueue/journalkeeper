/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.journalkeeper.core.server;

import io.journalkeeper.core.api.VoterState;
import io.journalkeeper.core.journal.Journal;
import io.journalkeeper.core.state.JournalKeeperState;
import io.journalkeeper.exceptions.IndexUnderflowException;
import io.journalkeeper.rpc.server.AsyncAppendEntriesRequest;
import io.journalkeeper.rpc.server.AsyncAppendEntriesResponse;
import io.journalkeeper.utils.state.ServerStateMachine;
import io.journalkeeper.utils.state.StateServer;
import io.journalkeeper.utils.threads.AsyncLoopThread;
import io.journalkeeper.utils.threads.ThreadBuilder;
import io.journalkeeper.utils.threads.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.NavigableMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;

import static io.journalkeeper.core.server.ThreadNames.STATE_MACHINE_THREAD;
import static io.journalkeeper.core.server.ThreadNames.VOTER_REPLICATION_REQUESTS_HANDLER_THREAD;

/**
 * @author LiYue
 * Date: 2019-09-10
 */
class Follower extends ServerStateMachine implements StateServer {
    private static final Logger logger = LoggerFactory.getLogger(Follower.class);
    /**
     * 节点上的最新状态 和 被状态机执行的最大日志条目的索引值（从 0 开始递增）
     */
    protected final JournalKeeperState state;
    private final Journal journal;
    private final URI serverUri;
    private final int currentTerm;
    private final VoterConfigManager voterConfigManager;
    private final Threads threads;
    private final NavigableMap<Long, JournalKeeperState> snapshots;
    /**
     * 待处理的asyncAppendEntries Request，按照request中的preLogTerm和prevLogIndex排序。
     */
    private final BlockingQueue<ReplicationRequestResponse> pendingAppendEntriesRequests;

    /**
     * Leader 日志当前的最大位置
     */
    private long leaderMaxIndex = -1L;


    private boolean readyForStartPreferredLeaderElection = false;

    Follower(Journal journal, JournalKeeperState state, URI serverUri, int currentTerm, VoterConfigManager voterConfigManager, Threads threads, NavigableMap<Long, JournalKeeperState> snapshots, int cachedRequests) {
        super(true);
        this.state = state;
        this.voterConfigManager = voterConfigManager;
        this.threads = threads;
        this.snapshots = snapshots;
        pendingAppendEntriesRequests = new PriorityBlockingQueue<>(cachedRequests,
                Comparator.comparing(ReplicationRequestResponse::getPrevLogTerm)
                        .thenComparing(ReplicationRequestResponse::getPrevLogIndex));

        this.journal = journal;
        this.serverUri = serverUri;
        this.currentTerm = currentTerm;
    }

    private AsyncLoopThread buildVoterReplicationHandlerThread() {
        return ThreadBuilder.builder()
                .name(threadName(VOTER_REPLICATION_REQUESTS_HANDLER_THREAD))
                .doWork(this::followerHandleAppendEntriesRequest)
                .sleepTime(0L, 0L)
                .onException(new DefaultExceptionListener(VOTER_REPLICATION_REQUESTS_HANDLER_THREAD))
                .daemon(true)
                .build();
    }

    private String threadName(String staticThreadName) {
        return serverUri + "-" + staticThreadName;
    }

    private String voterInfo() {
        return String.format("voterState: %s, currentTerm: %d, minIndex: %d, " +
                        "maxIndex: %d, commitIndex: %d, lastApplied: %d, uri: %s",
                VoterState.LEADER, currentTerm, journal.minIndex(),
                journal.maxIndex(), journal.commitIndex(), state.lastApplied(), serverUri.toString());
    }

    private int getTerm(long index) {
        try {
            return journal.getTerm(index);
        } catch (IndexUnderflowException e) {
            if (index + 1 == snapshots.firstKey()) {
                return snapshots.firstEntry().getValue().lastIncludedTerm();
            } else {
                throw e;
            }
        }
    }


    /**
     * 1. 如果 term < currentTerm返回 false
     * 如果 term > currentTerm且节点当前的状态不是FOLLOWER，将节点当前的状态转换为FOLLOWER；
     * 如果在prevLogIndex处的日志的任期号与prevLogTerm不匹配时，返回 false
     * 如果一条已经存在的日志与新的冲突（index 相同但是任期号 term 不同），则删除已经存在的日志和它之后所有的日志
     * 添加任何在已有的日志中不存在的条目
     * 如果leaderCommit > commitIndex，将commitIndex设置为leaderCommit和最新日志条目索引号中较小的一个
     */
    private void followerHandleAppendEntriesRequest() throws InterruptedException, IOException {

        ReplicationRequestResponse rr = pendingAppendEntriesRequests.take();
        AsyncAppendEntriesRequest request = rr.getRequest();
        AsyncAppendEntriesResponse response = null;

        // Reply false if log doesn’t contain an entry at prevLogIndex
        // whose term matches prevLogTerm
        if (request.getPrevLogIndex() < journal.minIndex() - 1 ||
                request.getPrevLogIndex() >= journal.maxIndex() ||
                getTerm(rr.getPrevLogIndex()) != request.getPrevLogTerm()
        ) {
            response = new AsyncAppendEntriesResponse(false, rr.getPrevLogIndex() + 1,
                    currentTerm, request.getEntries().size());
            rr.getResponseFuture().complete(response);
            return;
        }

        try {

            //  If an existing entry conflicts with a new one (same index
            //  but different terms), delete the existing entry and all that
            //  follow it
            //  Append any new entries not already in the log

            if (null != request.getEntries() && request.getEntries().size() > 0) {
                final long startIndex = request.getPrevLogIndex() + 1;

                // 如果要删除部分未提交的日志，并且待删除的这部分存在配置变更日志，则需要回滚配置
                voterConfigManager.maybeRollbackConfig(startIndex, journal, state.getConfigState());

                journal.compareOrAppendRaw(request.getEntries(), startIndex);

                // 非Leader（Follower和Observer）复制日志到本地后，如果日志中包含配置变更，则立即变更配置
                voterConfigManager.maybeUpdateNonLeaderConfig(request.getEntries(), state.getConfigState());
            }

            // If leaderCommit > commitIndex, set commitIndex =
            // min(leaderCommit, index of last new entry)
            if (request.getLeaderCommit() > journal.commitIndex()) {
                journal.commit(Math.min(request.getLeaderCommit(), journal.maxIndex()));
                threads.wakeupThread(threadName(STATE_MACHINE_THREAD));
            }

            if (leaderMaxIndex < request.getMaxIndex()) {
                leaderMaxIndex = request.getMaxIndex();
            }
            response = new AsyncAppendEntriesResponse(true, rr.getPrevLogIndex() + 1,
                    currentTerm, request.getEntries().size());
            rr.getResponseFuture().complete(response);
        } catch (Throwable t) {

            logger.warn("Exception when handle AsyncReplicationRequest, " +
                            "term: {}, leader: {}, prevLogIndex: {}, prevLogTerm: {}, entries: {}, leaderCommits: {}, " +
                            "{}.",
                    request.getTerm(), request.getLeader(), request.getPrevLogIndex(),
                    request.getPrevLogTerm(), request.getEntries().size(),
                    request.getLeaderCommit(), voterInfo(), t);
            rr.responseFuture.complete(new AsyncAppendEntriesResponse(t));
        }

    }


    CompletableFuture<AsyncAppendEntriesResponse> addAppendEntriesRequest(AsyncAppendEntriesRequest request) {

        ReplicationRequestResponse requestResponse = new ReplicationRequestResponse(request);
        if (serverState() == ServerState.RUNNING) {
            pendingAppendEntriesRequests.add(requestResponse);
//            if (request.getEntries() == null || request.getEntries().size() == 0) {
//                // 心跳直接返回成功
//                requestResponse.getResponseFuture().complete(new AsyncAppendEntriesResponse(true, request.getPrevLogIndex() + 1,
//                        currentTerm, request.getEntries().size()));
//            }
        } else {
            requestResponse.getResponseFuture().complete(
                    new AsyncAppendEntriesResponse(
                            new IllegalStateException(String.format("Follower not running! state: %s.",
                                    serverState().toString()))));
        }

        return requestResponse.getResponseFuture();
    }

    long getLeaderMaxIndex() {
        return leaderMaxIndex;
    }

    @Override
    protected void doStart() {
        super.doStart();
        threads.createThread(buildVoterReplicationHandlerThread());
        threads.startThread(threadName(VOTER_REPLICATION_REQUESTS_HANDLER_THREAD));
    }

    @Override
    protected void doStop() {
        // 等待所有的请求都处理完成
        while (!pendingAppendEntriesRequests.isEmpty()) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        threads.stopThread(threadName(VOTER_REPLICATION_REQUESTS_HANDLER_THREAD));
        threads.removeThread(threadName(VOTER_REPLICATION_REQUESTS_HANDLER_THREAD));
        super.doStop();
    }

    public boolean isReadyForStartPreferredLeaderElection() {
        return readyForStartPreferredLeaderElection;
    }

    public void setReadyForStartPreferredLeaderElection(boolean readyForStartPreferredLeaderElection) {
        this.readyForStartPreferredLeaderElection = readyForStartPreferredLeaderElection;
    }

    int getReplicationQueueSize() {
        return pendingAppendEntriesRequests.size();
    }

    static class ReplicationRequestResponse {
        private final AsyncAppendEntriesRequest request;
        private final CompletableFuture<AsyncAppendEntriesResponse> responseFuture;


        ReplicationRequestResponse(AsyncAppendEntriesRequest request) {
            this.request = request;
            responseFuture = new CompletableFuture<>();
        }

        AsyncAppendEntriesRequest getRequest() {
            return request;
        }

        CompletableFuture<AsyncAppendEntriesResponse> getResponseFuture() {
            return responseFuture;
        }

        int getPrevLogTerm() {
            return request.getPrevLogTerm();
        }

        long getPrevLogIndex() {
            return request.getPrevLogIndex();
        }
    }
}
