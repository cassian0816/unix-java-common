# AntX 行情服务（antx-quote-server）技术设计文档

> **版本**: v1.0  
> **日期**: 2026-03-10  
> **模块**: antx-quote-server  

---

## 目录

- [1. 概述](#1-概述)
- [2. 系统架构](#2-系统架构)
  - [2.1 架构总览](#21-架构总览)
  - [2.2 核心技术栈](#22-核心技术栈)
  - [2.3 系统部署架构](#23-系统部署架构)
- [3. 核心模块设计](#3-核心模块设计)
  - [3.1 模块总览](#31-模块总览)
  - [3.2 JRaft 一致性层（jraft 包）](#32-jraft-一致性层jraft-包)
  - [3.3 行情计算引擎（engine 包）](#33-行情计算引擎engine-包)
  - [3.4 数据存储层（store 包）](#34-数据存储层store-包)
  - [3.5 服务层（service 包）](#35-服务层service-包)
  - [3.6 对外接口层（controller / gRPC）](#36-对外接口层controller--grpc)
  - [3.7 数据模型层（model 包）](#37-数据模型层model-包)
- [4. 数据流设计](#4-数据流设计)
  - [4.1 链上事件消费主流程](#41-链上事件消费主流程)
  - [4.2 行情事件推送流程](#42-行情事件推送流程)
  - [4.3 快照与恢复流程](#43-快照与恢复流程)
- [5. JRaft 状态机设计](#5-jraft-状态机设计)
  - [5.1 状态机概述](#51-状态机概述)
  - [5.2 命令协议定义](#52-命令协议定义)
  - [5.3 onApply 执行流程](#53-onapply-执行流程)
  - [5.4 快照机制](#54-快照机制)
  - [5.5 Leader 生命周期管理](#55-leader-生命周期管理)
- [6. 行情数据结构设计](#6-行情数据结构设计)
  - [6.1 订单簿（Order Book）](#61-订单簿order-book)
  - [6.2 深度数据（Depth）](#62-深度数据depth)
  - [6.3 K线数据（Kline）](#63-k线数据kline)
  - [6.4 逐笔成交（Ticket）](#64-逐笔成交ticket)
  - [6.5 Ticker 24h 滚动统计](#65-ticker-24h-滚动统计)
  - [6.6 资金费率（Funding Rate）](#66-资金费率funding-rate)
  - [6.7 仓位（Position）](#67-仓位position)
- [7. 分片设计](#7-分片设计)
- [8. 存储设计](#8-存储设计)
  - [8.1 MySQL 持久化](#81-mysql-持久化)
  - [8.2 DynamoDB 持久化](#82-dynamodb-持久化)
  - [8.3 S3 快照存储](#83-s3-快照存储)
- [9. 服务发现与注册](#9-服务发现与注册)
- [10. 可观测性](#10-可观测性)
- [11. 已知问题与改进建议](#11-已知问题与改进建议)

---

## 1. 概述

`antx-quote-server` 是 AntX 交易所的**行情服务**，负责实时接收链上交易事件（Offchain Event），计算并维护全量行情数据（深度、K线、成交、Ticker、资金费率等），通过 gRPC 接口对外提供行情查询服务，并通过 Kafka 向下游推送行情变更事件。

**核心设计目标**：
- **一致性**：基于 SOFAJRaft 实现多副本状态复制，保证行情数据在集群中的强一致性
- **高可用**：通过 Raft 协议实现自动 Leader 选举和故障转移
- **高性能**：内存行情引擎 + Protobuf 序列化，实现低延迟行情计算
- **可恢复**：支持 Raft Snapshot + S3 快照备份，快速恢复行情状态

---

## 2. 系统架构

### 2.1 架构总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       antx-quote-server 集群                           │
│                                                                         │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐                        │
│  │  Node 1  │────│  Node 2  │────│  Node 3  │   (Raft Group)          │
│  │ (Leader) │     │(Follower)│     │(Follower)│                        │
│  └──────────┘     └──────────┘     └──────────┘                        │
└─────────────────────────────────────────────────────────────────────────┘
        ▲                    │                    │
        │ Raft Log           │ Replicate          │ Replicate
        │ Apply              ▼                    ▼

┌───────────┐    ┌───────────────┐    ┌────────────────────────────────┐
│   Kafka   │───▶│ OffchainEvent │───▶│   JRaft StateMachine           │
│(offchain_ │    │   Consumer    │    │  ┌──────────────────────────┐  │
│ event_v1) │    └───────────────┘    │  │     QuoteEngine          │  │
└───────────┘                         │  │  ┌─────────────────────┐ │  │
                                      │  │  │   MarketQuote (N)   │ │  │
┌───────────┐    ┌───────────────┐    │  │  │ ┌─────────────────┐ │ │  │
│   Kafka   │◀──│  QuoteEvent   │◀──│  │  │ │ OrderContainer  │ │ │  │
│(quote_    │    │   Producer    │    │  │  │ │ KlineContainer  │ │ │  │
│ event_v1) │    └───────────────┘    │  │  │ │ TickerContainer │ │ │  │
└───────────┘                         │  │  │ │ DepthContainer  │ │ │  │
      │                               │  │  │ │ TicketContainer │ │ │  │
      ▼                               │  │  │ │ FundingContainer│ │ │  │
┌───────────────┐                     │  │  │ │ PriceContainer  │ │ │  │
│ QuoteEvent    │                     │  │  │ │ PositionContainer│ │ │  │
│ StorageDb     │                     │  │  │ └─────────────────┘ │ │  │
│ Consumer      │                     │  │  └─────────────────────┘ │  │
└───────┬───────┘                     │  └──────────────────────────┘  │
        │                             └────────────────────────────────┘
        ▼
┌───────────────┐    ┌───────────┐    ┌───────────┐
│  MySQL (JPA)  │    │ DynamoDB  │    │    S3     │
│  t_kline      │    │ KlineBean │    │ Snapshot  │
│  t_funding    │    │ Funding   │    │  Backup   │
└───────────────┘    └───────────┘    └───────────┘
        ▲
        │ gRPC Query
┌───────────────┐
│  API Gateway  │
│  / Clients    │
└───────────────┘
```

### 2.2 核心技术栈

| 分类 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot | 应用基础框架 |
| 一致性 | SOFAJRaft 1.x | Raft 共识协议实现 |
| RPC | gRPC + grpc-spring-boot-starter | 行情查询接口 |
| 消息队列 | Apache Kafka | 链上事件消费 & 行情事件推送 |
| 序列化 | Protocol Buffers 3 | 命令/数据/快照序列化 |
| 持久化 | MySQL (JPA/Hibernate) | 历史 K线、资金费率存储 |
| 持久化 | AWS DynamoDB | 高性能行情数据查询（可选） |
| 快照备份 | AWS S3 | Raft 快照远程备份 |
| 服务注册 | Nacos | 服务发现与配置中心 |
| 可观测性 | OpenTelemetry | 指标采集 |
| 容器化 | Docker | 部署运行环境 |
| JVM | ZGC | 低延迟垃圾回收 |

### 2.3 系统部署架构

- 每个 JRaft Group 由 **3 个节点**组成（1 Leader + 2 Follower）
- 支持 **多 Shard 分片**，每个 Shard 独立一个 Raft Group
- 每个 Shard 监听独立的 Raft RPC 端口（`basePort + shardId`）
- Leader 节点负责：消费 Kafka、计算行情、推送行情事件
- Follower 节点通过 Raft 日志复制同步状态，可对外提供线性一致读

```
Node 1 (IP: 10.0.0.1)
├── Shard 0: JRaft Group "QuoteRaft0" @ port 7800
├── Shard 1: JRaft Group "QuoteRaft1" @ port 7801
└── gRPC Service @ port 9090

Node 2 (IP: 10.0.0.2)
├── Shard 0: JRaft Group "QuoteRaft0" @ port 7800
├── Shard 1: JRaft Group "QuoteRaft1" @ port 7801
└── gRPC Service @ port 9090
```

---

## 3. 核心模块设计

### 3.1 模块总览

```
exchange.antx.quote
├── QuoteApplication.java          # Spring Boot 启动类
├── QuoteException.java            # 业务异常
├── config/                        # 配置模块
│   ├── JraftConfiguration.java    # JRaft 相关 Bean 配置
│   ├── JraftProperties.java       # JRaft 配置属性
│   ├── QuoteConfiguration.java    # 行情服务配置
│   ├── QuoteProperties.java       # 行情属性配置
│   └── DynamoConfiguration.java   # DynamoDB 配置
├── jraft/                         # JRaft 一致性层
│   ├── JraftStateMachine.java     # Raft 状态机
│   ├── JraftGroupServiceWrapper.java  # Raft Group 管理
│   ├── JraftLeaderTermLifeCycle.java  # Leader 生命周期接口
│   ├── JraftNameRegister.java     # 服务注册接口
│   └── JraftNacosNameRegister.java # Nacos 服务注册实现
├── engine/                        # 行情计算引擎
│   ├── QuoteEngine.java           # 引擎主类（每 Shard 一个）
│   ├── QuoteCommand.java          # 命令定义
│   └── data/                      # 行情数据容器
│       ├── MarketQuote.java       # 单个交易对的行情数据聚合
│       ├── MetaData.java          # 元数据（Coin、Exchange）
│       ├── OrderContainer.java    # 订单簿管理
│       ├── DepthContainer.java    # 深度数据管理
│       ├── KlineContainer.java    # K线数据管理
│       ├── TickerContainer.java   # Ticker 统计管理
│       ├── TicketContainer.java   # 逐笔成交管理
│       ├── FundingContainer.java  # 资金费率管理
│       ├── PriceContainer.java    # 价格管理
│       └── PositionContainer.java # 仓位管理
├── store/                         # 状态存储层
│   ├── QuoteStore.java            # 全局状态存储（JRaft Node 管理）
│   ├── CmdFutureClosure.java      # 异步命令回调
│   ├── ExchangeStore.java         # 交易所数据存储
│   ├── ExchangeData.java          # 交易所数据实体（已重构）
│   ├── DepthData.java             # 深度数据
│   └── KlineData.java             # K线数据
├── service/                       # 业务服务层
│   ├── OffchainEventConsumer.java     # 链上事件 Kafka 消费者
│   ├── OffchainEventConsumerFactory.java  # 消费者工厂
│   ├── OffchainEventManager.java      # 链上事件处理管理器
│   ├── OffchainService.java           # 链上事件 gRPC 查询服务
│   ├── QuoteEventProducer.java        # 行情事件 Kafka 生产者
│   ├── QuoteEventProducerFactory.java # 生产者工厂
│   ├── QuoteEventStorageDbConsumer.java # 行情事件持久化消费者
│   ├── QuoteGrpcService.java          # gRPC 行情查询服务
│   ├── QuoteStorageManager.java       # 持久化存储管理
│   ├── SnapshotManager.java           # 快照管理
│   ├── ExchangeSequencer.java         # 交易对串行化执行器
│   ├── KlineFixManager.java           # K线修复管理
│   ├── OrderFixManager.java           # 订单修复管理
│   └── QuoteMetrics.java              # 监控指标
├── controller/                    # HTTP 接口
│   ├── InternalController.java    # 内部运维接口
│   └── JraftController.java      # JRaft 运维接口
├── model/                         # 数据模型
│   ├── OrderModel.java            # 订单模型
│   ├── PriceModel.java            # 价格节点模型
│   ├── PriceModelList.java        # 价格节点有序链表
│   ├── KlineModel.java            # K线模型
│   ├── TickerModel.java           # Ticker 模型
│   ├── TicketModel.java           # 成交记录模型
│   ├── DepthModel.java            # 深度模型
│   ├── BookOrderModel.java        # 盘口订单模型
│   ├── BookTickerModel.java       # 最优买卖价模型
│   ├── ExchangeModel.java         # 交易对模型
│   ├── CoinModel.java             # 币种模型
│   ├── PositionModel.java         # 仓位模型
│   └── KlineInterval.java         # K线周期枚举
├── db/                            # 数据库层
│   ├── entity/                    # JPA 实体
│   └── repository/                # JPA Repository
├── dynamo/                        # DynamoDB 层
│   ├── KlineBean.java             # DynamoDB K线 Bean
│   └── FundingRateBean.java       # DynamoDB 资金费率 Bean
└── util/                          # 工具类
    ├── Constants.java             # 常量定义
    ├── ShardId.java               # 分片 ID 计算
    ├── KlineId.java               # K线 ID 生成
    ├── KlineTime.java             # K线时间计算
    ├── SnowflakeIdGenerator.java  # 雪花 ID 生成器
    ├── DataPage.java              # 分页封装
    ├── PageOffsetData.java        # 游标分页数据
    ├── DynamoUtil.java            # DynamoDB 工具
    └── QuoteErrorKey.java         # 错误码定义
```

### 3.2 JRaft 一致性层（jraft 包）

#### 3.2.1 JraftGroupServiceWrapper

Raft Group 的全生命周期管理器，实现 `ApplicationRunner` 和 `DisposableBean` 接口：

- **启动时**：根据 `ShardId.maxShardNum` 创建多个 `RaftGroupService`，每个 Shard 独立一个 Raft Group
- **节点配置**：
  - Group ID: `{groupIdPrefix}{shardId}`（如 `QuoteRaft0`）
  - 数据目录: `{baseDataPath}/{shardId}/`（log / raft_meta / snapshot）
  - Raft RPC 端口: `{basePort} + {shardId}`
  - `ApplyTaskMode`: **Blocking**（串行应用日志条目）
  - `RaftOptions.sync`: false（异步刷盘，性能优先）
- **销毁时**：按顺序关闭所有 RaftGroupService

#### 3.2.2 JraftStateMachine

核心状态机实现类，实现 `com.alipay.sofa.jraft.StateMachine` 接口：

| 回调方法 | 职责 |
|---------|------|
| `onApply(Iterator)` | 应用已提交的 Raft 日志，调用 `QuoteEngine` 处理行情指令 |
| `onSnapshotSave(SnapshotWriter, Closure)` | 导出全量行情快照，异步保存到本地 + S3 |
| `onSnapshotLoad(SnapshotReader)` | 加载快照恢复行情状态（仅 Follower） |
| `onLeaderStart(long)` | 成为 Leader 时启动 Kafka 消费者、行情事件生产者等 |
| `onLeaderStop(Status)` | 失去 Leader 时关闭所有生命周期组件 |

**关键设计**：
- 状态机内部持有一个 `QuoteEngine` 实例，作为行情数据的唯一所有者
- 所有行情变更通过 `QuoteSubmitCmd` → Raft 日志 → `onApply` 回调，保证多副本一致
- `applyCmd` 在状态机线程（Disruptor 线程）同步执行业务逻辑
- `applyCmdAsync`（已 @Deprecated）将任务投递到 `ExchangeSequencer` 异步执行

#### 3.2.3 JraftLeaderTermLifeCycle

Leader 生命周期接口，当节点成为 Leader 时自动启动、失去 Leader 时自动关闭：

```java
public interface JraftLeaderTermLifeCycle {
    void start();
    ListenableFuture<Void> shutdown();
    
    interface Factory {
        JraftLeaderTermLifeCycle create(int shardId, long jraftLeaderTerm);
    }
}
```

**实现类**：
- `OffchainEventConsumer`：链上事件 Kafka 消费者（仅 Leader 消费）
- `QuoteEventProducer`：行情事件 Kafka 生产者（仅 Leader 推送）

### 3.3 行情计算引擎（engine 包）

#### 3.3.1 QuoteEngine

每个 Shard 对应一个 `QuoteEngine` 实例，管理该分片下所有交易对的行情数据。

**核心数据结构**：

```
QuoteEngine
├── shardId: int                                 # 分片 ID
├── marketQuoteMap: ConcurrentHashMap<Long, MarketQuote>  # exchangeId → 行情数据
├── messageOffsetMap: ConcurrentHashMap<String, KafkaMessageOffset>  # Kafka 消费位点
├── metaData: MetaData                          # 元数据（Coin/Exchange）
└── lastOffchainHeaderRef: AtomicReference<OffchainHeader>  # 最新链上区块头
```

**核心方法**：

| 方法 | 说明 |
|------|------|
| `processQuoteSubmitCmd(cmd)` | 处理提交指令，计算行情变更，返回 `QuoteChangeCmd` 列表 |
| `processQuoteChangeCmd(cmd, isLeader)` | 应用行情变更结果，生成 `QuoteEvent` 推送事件 |
| `importSnapshot(snapshot)` | 从快照恢复全量状态 |
| `exportSnapshot()` | 导出当前全量行情状态为快照 |

**指令处理分发**：

```
QuoteSubmitCmd
├── OffchainInternalEventCmd  → processOffchainEventCmd()
│   ├── ORDER_CREATE         → MarketQuote.processOrderCreate()
│   ├── ORDER_CANCEL         → MarketQuote.processOrderCancel()
│   ├── ORDER_FILL           → MarketQuote.processOrderFill()
│   ├── ORDER_TRIGGER        → MarketQuote.processOrderTrigger()
│   ├── PRICE_TICKS          → MarketQuote.processPriceTicks()
│   ├── FUNDING_TICKS        → MarketQuote.processFundingTicks()
│   ├── FUNDING_INDICES      → MarketQuote.processFundingIndices()
│   ├── COIN_UPDATE          → MetaData.putCoin()
│   └── EXCHANGE_UPDATE      → MetaData.putExchange()
├── EndKlineCmd               → processEndKlineCmd()
├── WriteDepthCmd             → processWriteDepth()
└── RemoveOrdersCmd           → processRemoveOrdersCmd()
```

#### 3.3.2 MarketQuote

单个交易对（Exchange）的全量行情数据聚合类，包含以下子容器：

```
MarketQuote (per exchangeId)
├── OrderContainer      # 订单簿管理
│   ├── PriceModelList asks  # 卖单价格链表（升序）
│   ├── PriceModelList bids  # 买单价格链表（降序）
│   ├── DepthContainer       # 深度计算
│   └── BookTickerModel      # 最优买卖价
├── KlineContainer (trade)   # 成交价 K线
├── KlineContainer (index)   # 指数价 K线
├── KlineContainer (oracle)  # 预言机价 K线
├── TickerContainer          # 24h Ticker 统计
├── TicketContainer          # 最近成交记录
├── FundingContainer         # 资金费率
├── PriceContainer           # 最新价格
├── PositionContainer        # 仓位管理
└── ConcurrentSkipListMap<Long, QuoteEvent>  # 行情事件队列
```

### 3.4 数据存储层（store 包）

#### QuoteStore

全局状态存储中心，管理所有 Shard 的 JRaft Node 和 QuoteEngine 引用：

**核心职责**：
1. **Raft 任务提交**：`doApplyTask()` — 构造 Raft Task 并提交到 Leader
2. **线性一致读**：`doReadSafe()` — 通过 `readIndex` 保证读取到已提交的最新状态
3. **Leader 状态跟踪**：维护 `leaderTerm` 和 `readyLeaderTerm` 双状态

**线性一致读优化**：
```
if (leaderTerm > 0 && leaderTerm <= readyLeaderTerm) {
    // 快路径：Leader 已就绪，直接本地读
    return supplier.get();
} else {
    // 慢路径：通过 readIndex 确认已提交再读
    node.readIndex(callback -> supplier.get());
}
```

### 3.5 服务层（service 包）

#### 3.5.1 OffchainEventConsumer（链上事件消费者）

- 实现 `JraftLeaderTermLifeCycle`，仅在 Leader 节点运行
- 消费 Kafka Topic: `offchain_event_v1`
- 消费流程：
  1. 启动时通过 `QuoteStore.getMessageOffsetMapSafe()` 获取上次消费位点，Seek 到对应位置
  2. 轮询 Kafka 消息，解析 `OffchainEventList`
  3. 通过 `OffchainEventManager` 进行事件排序和分组
  4. 将结果封装为 `QuoteSubmitCmd`，提交到 Raft 日志
  5. 每 **200ms** 触发一次深度刷新（`WriteDepthCmd`）
  6. 每 **60s** 触发一次 K线结束检查（`EndKlineCmd`）

#### 3.5.2 OffchainEventManager（链上事件管理器）

负责链上事件的**排序、去重、分组**：

- 按 `blockHeight` + `txHash` 聚合同一笔交易的所有事件
- 等待同一区块的所有事件到齐后（`eventSeqInBlock == eventTotalInBlock - 1`），按交易序号排序后批量处理
- 将混合事件按 `exchangeId` 拆分为独立的 `OffchainInternalEventCmd`
- 去重机制：通过 `OffchainHeader` 的 `blockHeight` 和 `eventSeqInBlock` 比较判定过期

#### 3.5.3 QuoteEventProducer（行情事件生产者）

- 实现 `JraftLeaderTermLifeCycle`，仅在 Leader 节点运行
- 生产 Kafka Topic: `quote_event_v1`
- 当 `QuoteEngine` 产生新的 `QuoteEvent` 时，通过 `notifyQueueCondition()` 通知生产者线程
- 生产者线程从 `QuoteStore` 安全读取事件队列，批量发送到 Kafka
- 发送成功后通过 `RemoveQuoteEventQueueCmd` 清理已发送的事件

#### 3.5.4 QuoteEventStorageDbConsumer（行情持久化消费者）

- 实现 `InitializingBean` / `DisposableBean`，应用启动即运行
- 消费 Kafka Topic: `quote_event_v1`
- 将行情事件（K线、资金费率等）持久化到 MySQL / DynamoDB

#### 3.5.5 ExchangeSequencer（交易对串行化执行器）

基于 Guava `ExecutionSequencer` 实现按 `exchangeId` 的任务串行化：

```
ExchangeSequencer
├── shardNum: 1 (当前强制单 shard，保证全局串行)
├── sequencerMap: Map<Integer, ExecutionSequencer>
└── threadPoolExecutor: ThreadPoolExecutor
```

> **注意**：当前 `shardNum` 被强制设为 1，原因是 `DepthContainer` 的 `TreeMap<BigDecimal, BigDecimal>` 不是线程安全的，并发访问会导致 `ConcurrentModificationException`。

#### 3.5.6 SnapshotManager（快照管理器）

| 功能 | 说明 |
|------|------|
| `saveRaftSnapshot()` | 将 `QuoteSnapshot` 序列化为 `snapshot_data.bin` + `snapshot_data.json` 存入 Raft snapshot 目录 |
| `loadRaftSnapshot()` | 从 Raft snapshot 目录加载 `QuoteSnapshot` |
| `saveSnapshotToS3()` | 将快照备份到 S3，路径: `{env}/{raftGroupId}/{datetime}/{md5}.bin` |
| `getSnapshotFromS3()` | 从 S3 加载快照，用于手动恢复 |

### 3.6 对外接口层（controller / gRPC）

#### 3.6.1 gRPC 行情查询接口（QuoteGrpcService）

| 接口 | 说明 |
|------|------|
| `getBookTicker` | 查询最优买卖价（支持批量） |
| `getDepth` | 查询深度数据（指定档位数） |
| `getKline` | 查询内存中的实时 K线数据 |
| `getHistoryKlinePage` | 分页查询历史 K线（从 DB / DynamoDB） |
| `getTicker` | 查询 24h Ticker 统计（支持批量） |
| `getTicket` | 查询最近成交记录 |
| `getOpenInterest` | 查询持仓量 |
| `getFundingRatePage` | 分页查询资金费率历史 |
| `getPrice` | 查询最新价格（指数/预言机） |

所有读请求通过 `QuoteStore.doReadSafe()` 实现线性一致读。

#### 3.6.2 HTTP 内部运维接口（InternalController）

| 接口 | 说明 |
|------|------|
| `GET /internal/removeOrders` | 手动移除指定价格的订单 |
| `POST /internal/fixHistoryKline` | 修复历史 K线数据 |
| `GET /internal/stopKafkaConsumer` | 停止 Kafka 消费者 |
| `GET /internal/startKafkaConsumer` | 启动 Kafka 消费者 |
| `GET /internal/loadSnapshotFromS3` | 从 S3 加载快照恢复状态 |
| `GET /internal/consumeOffchainEventFromServer` | 从 gRPC 服务补偿消费历史事件 |

#### 3.6.3 JRaft 运维接口（JraftController）

| 接口 | 说明 |
|------|------|
| `GET /jraft/getLeaderId` | 查询当前 Leader ID |
| `GET /jraft/listPeers` | 列出所有 Peer |
| `GET /jraft/listAlivePeers` | 列出存活 Peer |
| `GET /jraft/isLeader` | 当前节点是否为 Leader |
| `GET /jraft/getNodeState` | 查询节点状态 |
| `GET /jraft/describe` | 查询节点详细信息 |
| `POST /jraft/resetPeers` | 重置 Peer 配置 |
| `POST /jraft/transferLeadershipTo` | 手动转移 Leader |
| `POST /jraft/snapshot` | 手动触发快照 |

### 3.7 数据模型层（model 包）

#### 核心模型

| 模型 | 说明 | 关键字段 |
|------|------|---------|
| `OrderModel` | 订单 | orderId, exchangeId, price, size, isBuy, isOnBook, timeInForce, triggerType |
| `PriceModel` | 价格节点（链表节点）| price, totalSize, orderNum, firstOrder, lastOrder |
| `PriceModelList` | 价格节点有序链表 | `TreeMap<Long, PriceModel>` + 双向链表 |
| `KlineModel` | K线 | id, time, interval, OHLC, size, value, trades |
| `TickerModel` | 24h 统计 | open/close/high/low, size, value, priceChange, fundingRate |
| `TicketModel` | 成交记录 | ticketId, price, size, time, isBuyerMaker |
| `DepthModel` | 深度快照 | exchangeId, level, asks, bids, version |
| `BookTickerModel` | 最优买卖价 | bestAskPrice, bestAskSize, bestBidPrice, bestBidSize |
| `ExchangeModel` | 交易对配置 | exchangeId, baseCoin, quoteCoin, 精度参数 |
| `PositionModel` | 仓位 | subaccountId, exchangeId, openSize, openValue |

---

## 4. 数据流设计

### 4.1 链上事件消费主流程

```
                     Kafka (offchain_event_v1)
                              │
                              ▼
                   OffchainEventConsumer (Leader Only)
                              │
                              ▼
                   OffchainEventManager
                   ┌──────────┴──────────┐
                   │ 1. 按 blockHeight   │
                   │    聚合事件          │
                   │ 2. 按 txHash 排序   │
                   │ 3. 按 exchangeId    │
                   │    拆分子命令        │
                   └──────────┬──────────┘
                              │
                              ▼
              QuoteStore.applyQuoteSubmitCmd()
                              │
                              ▼
                   JRaft Leader: Task → Log
                              │
                   ─── Raft 日志复制 ───
                              │
                              ▼
              JraftStateMachine.onApply()
                              │
                              ▼
               QuoteEngine.processQuoteSubmitCmd()
               ┌──────────────┴──────────────┐
               │  计算行情变更               │
               │  返回 QuoteChangeCmd 列表   │
               └──────────────┬──────────────┘
                              │
                              ▼
               QuoteEngine.processQuoteChangeCmd()
               ┌──────────────┴──────────────┐
               │  1. 生成 QuoteEvent         │
               │  2. 放入事件队列            │
               │  3. 通知 QuoteEventProducer │
               └─────────────────────────────┘
```

### 4.2 行情事件推送流程

```
QuoteEngine 事件队列
        │
        ▼ (Leader Only)
QuoteEventProducer
        │
        ▼
Kafka (quote_event_v1)
        │
   ┌────┴────┐
   ▼         ▼
下游服务   QuoteEventStorageDbConsumer
             │
        ┌────┴────┐
        ▼         ▼
     MySQL     DynamoDB
   (t_kline)  (KlineBean)
```

### 4.3 快照与恢复流程

```
┌─ 周期触发（默认 3600 秒）或手动 /jraft/snapshot ─┐
│                                                   │
▼                                                   │
QuoteEngine.exportSnapshot()                        │
        │                                           │
   ┌────┴────────────────────┐                      │
   ▼                         ▼                      │
saveRaftSnapshot()    saveSnapshotToS3()            │
(本地磁盘 bin+json)   (S3 远程备份)                 │
                                                    │
┌─ Follower 启动 / 日志差距过大 ───────────────────┘
│
▼
onSnapshotLoad(SnapshotReader)
        │
        ▼
loadRaftSnapshot() → QuoteSnapshot
        │
        ▼
QuoteEngine.importSnapshot(snapshot)
```

---

## 5. JRaft 状态机设计

### 5.1 状态机概述

行情服务的状态机采用**「计算即日志」**模式：

- **输入**：链上事件（OffchainEvent）经过解析后封装为 `QuoteSubmitCmd`
- **计算**：在状态机 `onApply` 回调中同步执行行情计算（订单簿更新、K线计算、深度计算等）
- **输出**：行情变更结果（`QuoteChangeCmd`）用于生成推送事件

**当前架构的核心特征**：
- 行情计算逻辑在 Raft 状态机线程中同步执行
- 保证了所有副本的计算结果严格一致（确定性状态机）
- 但 CPU 密集型计算可能影响 Raft 心跳和日志复制性能

### 5.2 命令协议定义

```protobuf
// 顶层 Raft 命令
message QuoteJraftCmd {
    oneof cmd_type {
        QuoteSubmitCmd quote_submit_cmd = 1;       // 行情提交命令
        QuoteChangeCmd quote_change_cmd = 2;       // 行情变更命令
        RemoveQuoteEventQueueCmd ... = 3;           // 清理事件队列
        GetQuoteSnapshotCmd ... = 4;                // 获取快照
        LoadQuoteSnapshotCmd ... = 5;               // 加载快照
    }
}

// 行情提交命令（输入侧）
message QuoteSubmitCmd {
    uint64 exchange_id = 1;
    KafkaMessageOffset message_offset = 3;
    oneof cmd_type {
        OffchainInternalEventCmd offchain_internal_event_cmd = 11;  // 链上事件
        EndKlineCmd end_kline_cmd = 51;                             // K线结束
        WriteDepthCmd write_depth_cmd = 52;                         // 深度刷新
        RemoveOrdersCmd remove_orders_cmd = 61;                     // 移除订单
    }
}

// 行情变更命令（输出侧）
message QuoteChangeCmd {
    uint64 exchange_id = 1;
    repeated OrderChangeCmd order_change_cmd = 21;
    repeated KlineChangeCmd kline_change_cmd = 22;
    repeated DepthChangeCmd depth_change_cmd = 25;
    repeated TicketChangeCmd ticket_change_cmd = 26;
    repeated TickerChangeCmd ticker_change_cmd = 27;
    repeated BookTickerChangeCmd book_ticker_change_cmd = 28;
    repeated FundingChangeCmd funding_change_cmd = 32;
    repeated PositionChangeCmd position_change_cmd = 34;
    ...
}
```

### 5.3 onApply 执行流程

```java
onApply(Iterator iterator) {
    while (iterator.hasNext()) {
        // 1. 解析命令
        QuoteJraftCmd cmd = (closure != null) 
            ? closure.getCmd()               // Leader: 直接从 closure 获取
            : QuoteJraftCmd.parseFrom(data); // Follower: 从日志反序列化

        // 2. 分发执行
        switch (cmd.getCmdTypeCase()) {
            case QUOTE_SUBMIT_CMD:
                // 同步执行行情计算
                applyCmd(exchangeId, () -> executeQuoteSubmitCmd(cmd, isLeader), closure);
                break;
            case REMOVE_QUOTE_EVENT_QUEUE_CMD:
                applyCmd(exchangeId, () -> quoteEngine.removeQuoteEvent(cmd), closure);
                break;
            case LOAD_QUOTE_SNAPSHOT_CMD:
                applyCmd(0L, () -> loadQuoteSnapshotFromS3(cmd), closure);
                break;
        }

        // 3. 推进迭代器（在 finally 中确保不卡死）
        iterator.next();
    }
}
```

**executeQuoteSubmitCmd 内部流程**：
```
executeQuoteSubmitCmd(cmd, isLeader)
    │
    ▼
QuoteEngine.processQuoteSubmitCmd(cmd)
    │ ─── 计算行情变更 ───
    ▼
List<QuoteChangeCmd> changeCmdList
    │
    ▼ (for each changeCmd)
QuoteEngine.processQuoteChangeCmd(changeCmd, isLeader)
    │ ─── 生成 QuoteEvent ───
    │ ─── 放入事件队列 ───
    │ ─── isLeader? 通知 Producer ───
    ▼
```

### 5.4 快照机制

**快照内容**（`QuoteSnapshot`）：

```protobuf
message QuoteSnapshot {
    repeated ExchangeData exchange_data = 1;          // 全量交易对行情数据
    repeated KafkaMessageOffset message_offset = 2;    // Kafka 消费位点
    MetaData meta_data = 3;                            // 元数据
    OffchainHeader last_offchain_header = 4;           // 最新区块头
}
```

每个 `ExchangeData` 包含完整的订单簿、K线、成交、Ticker 等数据。

**保存流程**：
1. 状态机线程中同步调用 `QuoteEngine.exportSnapshot()`（O(N) 遍历内存数据）
2. 使用内部单线程池 `internalThreadPoolExecutor` 异步序列化并写入磁盘
3. 使用配置线程池 `threadPoolExecutor` 异步上传 S3 备份

**加载流程**：
1. 从磁盘读取 `snapshot_data.bin`
2. 反序列化为 `QuoteSnapshot`
3. 调用 `QuoteEngine.importSnapshot()` 恢复全量状态

### 5.5 Leader 生命周期管理

```
onLeaderStart(leaderTerm)
    │
    ├── 1. 设置 leaderTerm
    ├── 2. 创建 JraftLeaderTermLifeCycle 实例
    │      ├── OffchainEventConsumer  → 消费链上事件
    │      └── QuoteEventProducer    → 推送行情事件
    ├── 3. 更新 QuoteStore 的 LeaderTerm
    ├── 4. 提交到线程池异步启动各 LifeCycle.start()
    └── 5. Nacos 服务注册（如果启用）

onLeaderStop(status)
    │
    ├── 1. 清零 leaderTerm
    ├── 2. 逆序关闭所有 LifeCycle.shutdown()
    ├── 3. 等待所有 shutdown Future 完成
    └── 4. Nacos 服务注销
```

---

## 6. 行情数据结构设计

### 6.1 订单簿（Order Book）

采用**价格-时间优先**的双向链表 + TreeMap 索引结构：

```
OrderContainer
├── orderMap: HashMap<Long, OrderModel>           # orderId → 订单 O(1) 查找
├── triggerOrderMap: HashMap<Long, OrderModel>    # 条件单
├── asks: PriceModelList (TreeMap 升序)           # 卖盘
│   └── TreeMap<Long, PriceModel>
│       ├── PriceModel(100) → OrderA → OrderB → OrderC  (FIFO)
│       ├── PriceModel(101) → OrderD
│       └── PriceModel(102) → OrderE → OrderF
└── bids: PriceModelList (TreeMap 降序)           # 买盘
    └── TreeMap<Long, PriceModel>
        ├── PriceModel(99) → OrderG → OrderH
        ├── PriceModel(98) → OrderI
        └── PriceModel(97) → OrderJ → OrderK
```

**PriceModelList**：价格节点维护双向链表 + TreeMap 索引
- `addOrder(order)`: O(log N) 查找/创建价格节点 + O(1) 链表追加
- `removeOrder(order)`: O(1) 链表删除 + O(log N) 价格节点清理
- `getBestPriceNode()`: O(1)（TreeMap.firstEntry）

### 6.2 深度数据（Depth）

```
DepthContainer
├── asks: TreeMap<BigDecimal, BigDecimal>  (升序)    # 价格 → 总量
├── bids: TreeMap<BigDecimal, BigDecimal>  (降序)    # 价格 → 总量
├── baseDepthWrapper (level=200)                     # 全量深度快照
└── subDepthWrapperMap                               # 子深度快照
    └── level=15                                     # 15 档深度
```

**深度更新策略**：
1. 订单变更时调用 `onPriceChanged()` 更新 TreeMap
2. 每 200ms 由 `WriteDepthCmd` 触发 `writeToDepth()`
3. `writeToDepth()` 遍历 TreeMap 构建快照，与上一次快照做 diff 生成增量变更
4. 增量变更（`DepthType.CHANGED`）+ 全量快照（`DepthType.SNAPSHOT`）同时推送

### 6.3 K线数据（Kline）

支持三种价格类型 × 多周期 K线：

| 价格类型 | 说明 |
|---------|------|
| `PRICE_TYPE_LAST` | 成交价 K线（trade kline） |
| `PRICE_TYPE_INDEX` | 指数价 K线 |
| `PRICE_TYPE_ORACLE` | 预言机价 K线 |

**K线周期**：M1, M5, M15, M30, H1, H4, H8, H12, D1, W1, MN1 等

```
KlineContainer
├── currentKlineMap: Map<Interval, KlineModel>            # 当前未完成 K线
├── currentFinishKlineMap: Map<Interval, KlineModel>      # 刚完成的 K线
├── lastKlineMap: Map<Interval, KlineModel>               # 上一周期 K线
└── klineMap: Map<Interval, ConcurrentLinkedHashMap<Long, KlineModel>>  # 历史 K线（LRU，最多 1000 条/周期）
```

**K线计算流程**：
1. 每笔成交调用 `computeKline(ticket)`，累加到当前 K线
2. 当 `ticket.time >= currentKlineTime + interval` 时触发 `finishKline()`
3. `finishKline()` 将当前 K线归档到历史，创建新的当前 K线
4. 同时通过 `EndKlineCmd`（每 60s 触发）保证空闲时段 K线正确切换

### 6.4 逐笔成交（Ticket）

使用 `ConcurrentLinkedHashMap`（LRU）存储最近 1000 条成交：

```
TicketContainer
└── ticketMap: ConcurrentLinkedHashMap<String, TicketModel>  # ticketId → 成交记录
```

成交记录由 `OrderFill` 事件生成，包含价格、数量、方向、时间等信息。

### 6.5 Ticker 24h 滚动统计

基于 **15 分钟 K线窗口**滚动计算 24h 统计数据：

```
TickerContainer
├── ticker: TickerModel              # 24h 统计数据
├── highNodeList: LinkedList         # 高价候选队列（单调队列）
├── lowNodeList: LinkedList          # 低价候选队列（单调队列）
└── klineList: LinkedList<KlineModel> # 24h 内 M15 K线列表
```

**滚动逻辑**：
1. 每笔成交更新 Ticker 的 close、high、low、size、value、trades
2. 当 M15 K线完成时调用 `rollingTicker()`
3. 滑动窗口超过 24h 的部分被移除，重新计算 open/high/low 等

### 6.6 资金费率（Funding Rate）

```
FundingContainer
├── currentFundingRate: FundingRate   # 当前资金费率
└── currentFundingFee: FundingFee    # 当前资金费用
```

数据来源于链上 `FundingTicks` 和 `FundingIndices` 事件。

### 6.7 仓位（Position）

```
PositionContainer
├── positionMap: Map<String, PositionModel>  # positionKey → 仓位
└── totalPosition: TotalPosition             # 总持仓量
```

仅在永续合约模式下维护仓位数据，用于计算 `openInterest`。

---

## 7. 分片设计

行情服务通过 `ShardId` 实现基于交易对 ID 的分片：

```java
public class ShardId {
    public static int maxShardNum;  // 通过 JVM 参数 -DmaxShardNum 配置，默认 1
    
    public static int getShardId(long exchangeId) {
        return Long.valueOf(exchangeId).hashCode() % maxShardNum;
    }
}
```

| 属性 | 说明 |
|------|------|
| 分片数量 | 通过 `maxShardNum` 配置，每个 Shard 独立一个 Raft Group |
| 分片方式 | `exchangeId.hashCode() % maxShardNum` |
| Raft 端口 | `basePort + shardId` |
| 数据隔离 | 每个 Shard 拥有独立的 `QuoteEngine`、`MarketQuote` 集合 |
| Kafka 消费者 | 每个 Shard 独立消费者组：`antx-quote-server-{shardId}` |

---

## 8. 存储设计

### 8.1 MySQL 持久化

**K线表 `t_kline`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `kline_id` | BIGINT PK | 雪花 ID |
| `exchange_id` | BIGINT | 交易对 ID |
| `kline_type` | INT | K线类型（1m/5m/...） |
| `kline_time` | BIGINT | K线起始时间戳 |
| `price_type` | INT | 价格类型（last/index/oracle） |
| `trades` | BIGINT | 成交笔数 |
| `open/close/high/low` | VARCHAR | OHLC |
| `size/value` | VARCHAR | 成交量/成交额 |

索引：`idx_exchange_id_kline_type_price_type_kline_time`

**资金费率表 `t_funding_rate`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `exchange_id` | BIGINT | 交易对 ID |
| `funding_calculate_time` | BIGINT | 计算时间 |
| `funding_rate` | VARCHAR | 资金费率 |
| `is_settlement` | BIT | 是否结算 |
| ... | ... | 其他费率相关字段 |

复合主键：`(exchange_id, funding_calculate_time, premium_index_calculate_time)`

### 8.2 DynamoDB 持久化

通过 `DynamoConfiguration` 可选配置，用于高性能查询场景：

- `KlineBean`：K线数据，支持按 `exchange_id + kline_type + price_type + kline_time` 索引查询
- `FundingRateBean`：资金费率数据

通过 Nacos 配置 `antx.quote.enable-read-dynamo` 动态切换读取源。

### 8.3 S3 快照存储

快照路径格式：`{env}/{raftGroupId}/{datetime}/{md5}.bin`

示例：`testnet/0/2026_03_10_12_00_00_000/abc123.bin`

---

## 9. 服务发现与注册

- 基于 **Nacos** 实现服务注册与发现
- Leader 节点注册元数据：
  ```json
  {
    "jraft.group-id": "QuoteRaft0",
    "jraft.server-id": "10.0.0.1:7800",
    "jraft.leader-term": "5"
  }
  ```
- Follower 节点注册 `leader-term=0`，可用于区分 Leader/Follower
- 下游服务通过 Nacos 发现 Leader 节点进行写操作

---

## 10. 可观测性

### 10.1 OpenTelemetry 指标

| 指标名 | 类型 | 说明 |
|-------|------|------|
| `antx_jraft_leader_term` | Gauge | 当前 Leader Term |
| `antx_quote_consume_event_block_delay_time_millis` | Histogram | Kafka 消费延迟（毫秒） |
| `antx_quote_consume_event_block_height` | Gauge | 当前处理到的区块高度 |

### 10.2 日志

- 框架：Logback（`logback-spring.xml`）
- 日志目录：`/data/logs/antx-quote-server.log`
- 滚动策略：按小时滚动，单文件最大 1GB，最多保留 30 天，总计 100GB
- 关键日志标签：`[onApply]`、`[processQuoteSubmitCmd]`、`[onSnapshotSave]`、`[onSnapshotLoad]`

### 10.3 Tracing

通过 OpenTelemetry Java Agent 自动注入，启动参数：
```bash
-javaagent:/application/opentelemetry-javaagent.jar
```

---

## 11. 已知问题与改进建议

### 11.1 当前已知问题

#### P0: ConcurrentModificationException

**现象**：`DepthContainer.writeBookOrderList()` 遍历 `TreeMap` 时抛出 `ConcurrentModificationException`

**根因**：`asks` / `bids`（`TreeMap`）在 Raft 状态机线程中被更新（通过 `onPriceChanged`），同时在定时刷新深度时被遍历（通过 `writeToDepth`），两者可能在不同线程执行。

**当前缓解**：将 `ExchangeSequencer.shardNum` 强制设为 1，保证全局串行化执行。

**建议修复**：
- 方案 A：确保所有对 `asks/bids` 的读写操作都在同一线程中串行执行
- 方案 B：在 `writeToDepth()` 中对 `asks/bids` 做快照拷贝后再遍历
- 方案 C：将深度计算结果作为 `QuoteChangeCmd` 的一部分直接在状态机中同步处理

#### P1: NoClassDefFoundError - cometbft.abci.v2.Service

**现象**：`PbUtil.<clinit>` 引用了 `cometbft.abci.v2.Service`，但运行时 classpath 中缺失

**根因**：`antx-proto` jar 包中可能未包含该类，或部署时使用了旧版 jar

**建议**：确认 `antx-proto` 的打包配置，保证所有 protobuf 生成类被包含在 jar 中

### 11.2 架构改进建议

#### 建议一：行情计算从状态机中剥离

**当前**：行情计算（K线、深度、Ticker 等）全部在 `onApply` 回调中同步执行

**问题**：CPU 密集计算阻塞 Raft Disruptor 线程，影响选举超时和日志复制

**建议**：
```
当前: Kafka → QuoteSubmitCmd → Raft Log → onApply(计算+存储)

优化: Kafka → 计算行情 → QuoteChangeCmd → Raft Log → onApply(仅存储)
```

- 在 Kafka 消费线程中完成行情计算，将确定性结果封装为 `QuoteChangeCmd`
- 状态机 `onApply` 只执行简单的增删改操作
- 注意：计算必须基于确定性输入，保证重放一致性

#### 建议二：细粒度并发模型

**当前**：`ExchangeSequencer.shardNum=1`，全局串行

**建议**：
- 不同 `exchangeId` 的行情计算可以并行（它们的数据是隔离的）
- 仅 `exchangeId=0` 的全局操作（EndKline / WriteDepth）需要与所有 exchange 串行
- 实现类似读写锁 / 栅栏机制的 Sequencer

#### 建议三：状态机失败状态语义

**当前**：`applyCmd` 中 catch 异常后 `closure.run(Status.OK())`

**建议**：失败时返回非 OK 状态，便于上游感知：
```java
closure.run(new Status(RaftError.EINTERNAL.getNumber(), 
    "applyCmd failed: " + e.getMessage()));
```

---

> **文档维护说明**：本文档基于 `antx-quote-server` v1.0.0-SNAPSHOT 代码生成，后续架构变更请同步更新。

