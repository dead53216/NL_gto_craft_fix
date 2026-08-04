package com.gtocraftfix.lpcalc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/**
 * 圖＋SCC＋拓撲（§4.6／§6.1）。兩層邊集：
 * <ul>
 *   <li>路由邊（傳播用）：K → selectedPattern(K)、P → 每個解析後輸入 key。</li>
 *   <li>副產供給邊（僅 SCC 偵測用）：K → Q，當 getCraftingFor(K) 為空且閉包內樣板 Q 的輸出含 K。
 *       Tarjan 跑聯集——Kroll 跨樣板環（MgCl2 只是副產物）才會縮成一個 SCC；
 *       副產供給邊不參與傳播路由（絕不因副產物需求驅動 runs，SCC 內除外）。</li>
 * </ul>
 * 節點模型：key 節點 [0,nk)、樣板節點 [nk,nk+np)。樣板自迴圈（輸出=輸入）在此模型下
 * 自然形成大小 ≥2 的 SCC，故 isCyclicScc 只需檢查節點數 > 1。
 */
final class LpGraph {

    private final LpCraftSnapshot snap;
    private final List<AEKey> keyList = new ArrayList<>();
    private final Map<AEKey, Integer> keyIdx = new HashMap<>();
    private final List<LpPattern> patList = new ArrayList<>();
    private final Map<IPatternDetails, Integer> patIdx = new HashMap<>();
    private int nk;
    private int np;

    private int[] sccOfNode;
    private int sccCount;
    private boolean[] sccCyclic;
    private int[] topoScc;
    /** 縮點 DAG 邊（消費者 → 生產者，含副產供給邊）；等待邊另存並在 reachable 中一併走訪 */
    private List<IntArrayList> sccAdj;
    private List<IntArrayList> waitAdj;
    private List<List<AEKey>> sccKeys;
    private List<List<IPatternDetails>> sccPats;
    private boolean multiplePaths;
    /** 無自己樣板、僅靠閉包樣板副產出供給的 key（傳播器掛起重試用） */
    private final java.util.Set<AEKey> byproductSuppliedKeys = new java.util.HashSet<>();
    /** reachable() 走訪標記重用（世代戳，免每次配置 boolean[sccCount]） */
    private int[] visitMark;
    private int visitGen;

    private LpGraph(LpCraftSnapshot snap) {
        this.snap = snap;
    }

    static LpGraph build(LpCraftSnapshot snap) throws LpFallbackException {
        var g = new LpGraph(snap);
        g.buildInner();
        return g;
    }

    private void buildInner() {
        // ---- 節點索引 ----
        for (var k : snap.patternsByKey.keySet()) {
            intern(k);
        }
        for (var k : snap.emitable) {
            intern(k);
        }
        nk = keyList.size();
        for (var e : snap.compiled.entrySet()) {
            if (!patIdx.containsKey(e.getKey())) {
                patIdx.put(e.getKey(), patList.size());
                patList.add(e.getValue());
            }
        }
        np = patList.size();
        int n = nk + np;

        // ---- 邊集 ----
        var adj = new ArrayList<IntArrayList>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new IntArrayList());
        }
        // 路由邊：K → selectedPattern(K)（傳播只用首位候選；分攤語意靠鐵則 10 回退兜住）
        for (var e : snap.patternsByKey.entrySet()) {
            var pats = e.getValue();
            if (!pats.isEmpty()) {
                adj.get(keyIdx.get(e.getKey())).add(nk + patIdx.get(pats.get(0)));
            }
        }
        // 路由邊：P → 每個解析後輸入 key
        for (int j = 0; j < np; j++) {
            var lp = patList.get(j);
            for (var in : lp.inKey) {
                Integer ki = keyIdx.get(in);
                if (ki != null) {
                    adj.get(nk + j).add((int) ki);
                }
            }
        }
        // 副產供給邊（僅 SCC 偵測）：無樣板 key K ← 閉包樣板 Q 的輸出，邊向 K → Q。
        // output→樣板 反向索引一次建好（O(Σoutputs)），避免「無樣板 key × np」全掃
        var producersByOutput = new HashMap<AEKey, IntArrayList>();
        for (int j = 0; j < np; j++) {
            for (var out : patList.get(j).outKey) {
                producersByOutput.computeIfAbsent(out, kk -> new IntArrayList()).add(j);
            }
        }
        for (var e : snap.patternsByKey.entrySet()) {
            if (!e.getValue().isEmpty()) {
                continue;
            }
            var producers = producersByOutput.get(e.getKey());
            if (producers == null) {
                continue;
            }
            int ki = keyIdx.get(e.getKey());
            for (int t = 0; t < producers.size(); t++) {
                adj.get(ki).add(nk + producers.getInt(t));
            }
            byproductSuppliedKeys.add(e.getKey());
        }

        tarjan(n, adj);

        // ---- 縮點 DAG（邊去重）＋節點歸組 ----
        sccAdj = new ArrayList<>(sccCount);
        waitAdj = new ArrayList<>(sccCount);
        sccKeys = new ArrayList<>(sccCount);
        sccPats = new ArrayList<>(sccCount);
        for (int s = 0; s < sccCount; s++) {
            sccAdj.add(new IntArrayList());
            waitAdj.add(new IntArrayList());
            sccKeys.add(new ArrayList<>());
            sccPats.add(new ArrayList<>());
        }
        for (int i = 0; i < nk; i++) {
            sccKeys.get(sccOfNode[i]).add(keyList.get(i));
        }
        for (int j = 0; j < np; j++) {
            sccPats.get(sccOfNode[nk + j]).add(patList.get(j).details);
        }
        var seen = new LongOpenHashSet();
        int[] indeg = new int[sccCount];
        for (int u = 0; u < n; u++) {
            int su = sccOfNode[u];
            var edges = adj.get(u);
            for (int t = 0; t < edges.size(); t++) {
                int sv = sccOfNode[edges.getInt(t)];
                if (su != sv && seen.add(((long) su << 32) | (sv & 0xFFFFFFFFL))) {
                    sccAdj.get(su).add(sv);
                    indeg[sv]++;
                }
            }
        }

        // ---- 拓撲序（Kahn；邊向消費者→生產者 ⇒ 消費者在前）----
        topoScc = new int[sccCount];
        var q = new ArrayDeque<Integer>();
        for (int s = 0; s < sccCount; s++) {
            if (indeg[s] == 0) {
                q.add(s);
            }
        }
        int pos = 0;
        while (!q.isEmpty()) {
            int s = q.poll();
            topoScc[pos++] = s;
            var out = sccAdj.get(s);
            for (int t = 0; t < out.size(); t++) {
                if (--indeg[out.getInt(t)] == 0) {
                    q.add(out.getInt(t));
                }
            }
        }
        if (pos != sccCount) {
            // 縮點圖必為 DAG；到不了這裡（防禦）
            throw new LpFallbackException(FallbackReason.SNAPSHOT_ERROR, "condensation not DAG");
        }
        multiplePaths = !snap.multiCandidate.isEmpty();
        visitMark = new int[sccCount];
    }

    private void intern(AEKey k) {
        keyIdx.computeIfAbsent(k, kk -> {
            keyList.add(kk);
            return keyList.size() - 1;
        });
    }

    /** 迭代版 Tarjan（閉包可到 4096 key，遞迴會爆棧）。 */
    private void tarjan(int n, List<IntArrayList> adj) {
        sccOfNode = new int[n];
        java.util.Arrays.fill(sccOfNode, -1);
        int[] index = new int[n];
        int[] low = new int[n];
        java.util.Arrays.fill(index, -1);
        boolean[] onStack = new boolean[n];
        var stack = new IntArrayList();
        int[] sccSize = new int[n];
        int counter = 0;
        sccCount = 0;

        int[] callNode = new int[n + 1];
        int[] callEdge = new int[n + 1];
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) {
                continue;
            }
            int sp = 0;
            callNode[0] = root;
            callEdge[0] = 0;
            index[root] = low[root] = counter++;
            stack.add(root);
            onStack[root] = true;
            while (sp >= 0) {
                int v = callNode[sp];
                var edges = adj.get(v);
                if (callEdge[sp] < edges.size()) {
                    int w = edges.getInt(callEdge[sp]++);
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        stack.add(w);
                        onStack[w] = true;
                        sp++;
                        callNode[sp] = w;
                        callEdge[sp] = 0;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    if (low[v] == index[v]) {
                        int size = 0;
                        int w;
                        do {
                            w = stack.removeInt(stack.size() - 1);
                            onStack[w] = false;
                            sccOfNode[w] = sccCount;
                            size++;
                        } while (w != v);
                        sccSize[sccCount] = size;
                        sccCount++;
                    }
                    sp--;
                    if (sp >= 0) {
                        low[callNode[sp]] = Math.min(low[callNode[sp]], low[v]);
                    }
                }
            }
        }
        sccCyclic = new boolean[sccCount];
        for (int s = 0; s < sccCount; s++) {
            sccCyclic[s] = sccSize[s] > 1;
        }
    }

    // ---- 查詢 API ----

    LpPattern selectedPattern(AEKey k) {
        var pats = snap.patternsByKey.get(k);
        if (pats == null || pats.isEmpty()) {
            return null;
        }
        return snap.compiled.get(pats.get(0));
    }

    boolean multiplePaths() {
        return multiplePaths;
    }

    int sccOf(AEKey k) {
        Integer i = keyIdx.get(k);
        return i == null ? -1 : sccOfNode[i];
    }

    int sccOfPattern(IPatternDetails p) {
        Integer j = patIdx.get(p);
        return j == null ? -1 : sccOfNode[nk + j];
    }

    boolean isCyclicScc(int sccId) {
        return sccId >= 0 && sccCyclic[sccId];
    }

    /** 縮點 DAG 反拓撲序：消費者在前、生產者在後。 */
    int[] topoOrder() {
        return topoScc;
    }

    List<AEKey> keysOfScc(int sccId) {
        return sccKeys.get(sccId);
    }

    List<IPatternDetails> patternsOfScc(int sccId) {
        return sccPats.get(sccId);
    }

    /** 非循環 SCC 的唯一 key（純樣板節點的 SCC → null）。傳播的節點走訪用。 */
    AEKey keyOfSingletonScc(int sccId) {
        var ks = sccKeys.get(sccId);
        return ks.size() == 1 ? ks.get(0) : null;
    }

    /** k 是否為副產供給鏈 key（無自己樣板、僅靠閉包樣板副產出）。 */
    boolean byproductSupplied(AEKey k) {
        return byproductSuppliedKeys.contains(k);
    }

    /** from 是否可達 to（縮點邊 ∪ 累積等待邊）。等待邊會動態增加，故用 BFS（SCC 數量小）。 */
    boolean reachable(int fromScc, int toScc) {
        if (fromScc == toScc) {
            return true;
        }
        int gen = ++visitGen;
        var q = new ArrayDeque<Integer>();
        q.add(fromScc);
        visitMark[fromScc] = gen;
        while (!q.isEmpty()) {
            int s = q.poll();
            var a = sccAdj.get(s);
            for (int t = 0; t < a.size(); t++) {
                int v = a.getInt(t);
                if (v == toScc) {
                    return true;
                }
                if (visitMark[v] != gen) {
                    visitMark[v] = gen;
                    q.add(v);
                }
            }
            var w = waitAdj.get(s);
            for (int t = 0; t < w.size(); t++) {
                int v = w.getInt(t);
                if (v == toScc) {
                    return true;
                }
                if (visitMark[v] != gen) {
                    visitMark[v] = gen;
                    q.add(v);
                }
            }
        }
        return false;
    }

    /** surplus 折抵是否安全（§6.2 守衛）：生產者不可達消費者才可等待。 */
    boolean canWait(int consumerScc, int producerScc) {
        if (consumerScc < 0 || producerScc < 0) {
            return false;
        }
        return consumerScc != producerScc && !reachable(producerScc, consumerScc);
    }

    /** 等待邊跨全部折抵決策累積（一筆通過就記邊，影響後續判定）。 */
    void addWaitEdge(int consumerScc, int producerScc) {
        waitAdj.get(consumerScc).add(producerScc);
    }
}
