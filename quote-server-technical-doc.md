# Quote Server 技术文档

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 系统架构](#2-系统架构)
- [3. 技术栈与依赖](#3-技术栈与依赖)
- [4. 项目结构](#4-项目结构)
- [5. 行情处理核心流程](#5-行情处理核心流程)
  - [5.1 总体数据流](#51-总体数据流)
  - [5.2 事件消费流程](#52-事件消费流程)
  - [5.3 JRaft 共识处理流程](#53-jraft-共识处理流程)
  - [5.4 行情变更事件分发流程](#54-行情变更事件分发流程)
- [6. 核心功能模块](#6-核心功能模块)
  - [6.1 订单簿管理 (OrderContainer)](#61-订单簿管理-ordercontainer)
  - [6.2 深度数据管理 (DepthContainer)](#62-深度数据管理-depthcontainer)
  - [6.3 K线管理 (KlineContainer)](#63-k线管理-klinecontainer)
  - [6.4 24小时行情统计 (TickerContainer)](#64-24小时行情统计-tickercontainer)
  - [6.5 最近成交管理 (TicketContainer)](#65-最近成交管理-ticketcontainer)
  - [6.6 资金费率管理 (FundingContainer)](#66-资金费率管理-fundingcontainer)
  - [6.7 外部价格管理 (PriceContainer)](#67-外部价格管理-pricecontainer)
  - [6.8 仓位管理 (PositionContainer)](#68-仓位管理-positioncontainer)
  - [6.9 元数据管理 (MetaData)](#69-元数据管理-metadata)
- [7. 数据模型详解](#7-数据模型详解)
  - [7.1 OrderModel - 订单模型](#71-ordermodel---订单模型)
  - [7.2 KlineModel - K线模型](#72-klinemodel---k线模型)
  - [7.3 TickerModel - 行情统计模型](#73-tickermodel---行情统计模型)
  - [7.4 TicketModel - 成交记录模型](#74-ticketmodel---成交记录模型)
  - [7.5 DepthModel - 深度快照模型](#75-depthmodel---深度快照模型)
  - [7.6 BookOrderModel - 盘口档位模型](#76-bookordermodel---盘口档位模型)
  - [7.7 BookTickerModel - 最优买卖价模型](#77-booktickmodel---最优买卖价模型)
  - [7.8 PositionModel - 仓位模型](#78-positionmodel---仓位模型)
  - [7.9 PriceModel - 价格档位模型](#79-pricemodel---价格档位模型)
  - [7.10 ExchangeModel - 交易对模型](#710-exchangemodel---交易对模型)
  - [7.11 KlineInterval - K线周期枚举](#711-klineinterval---k线周期枚举)
- [8. gRPC 服务接口](#8-grpc-服务接口)
- [9. 分布式架构](#9-分布式架构)
  - [9.1 JRaft 共识层](#91-jraft-共识层)
  - [9.2 分片策略](#92-分片策略)
  - [9.3 快照管理](#93-快照管理)
- [10. 持久化层](#10-持久化层)
  - [10.1 MySQL 数据库表结构](#101-mysql-数据库表结构)
  - [10.2 DynamoDB 表结构](#102-dynamodb-表结构)
- [11. 消息队列](#11-消息队列)
- [12. 配置说明](#12-配置说明)
- [13. 关键常量](#13-关键常量)

---

## 1. 项目概述

`quote-server` 是加密货币衍生品交易所的**行情服务**，负责接收链上（offchain）事件，维护实时行情状态（订单簿、深度、K线、Ticker、成交记录、资金费率、仓位等），并通过 gRPC 和 Kafka 对外提供行情数据服务。

系统基于 **JRaft 分布式共识协议** 构建，确保多节点间行情数据的强一致性，支持按交易对进行分片以实现水平扩展。

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        外部客户端                                │
│              (通过 gRPC 查询行情 / 通过 Kafka 订阅变更)           │
└──────────┬──────────────────────────────────────┬───────────────┘
           │ gRPC                                  │ Kafka (quote_event_v1)
           ▼                                       ▲
┌──────────────────────┐              ┌────────────────────────────┐
│   QuoteGrpcService   │              │   QuoteEventProducer       │
│   (gRPC查询服务)      │              │   (行情变更事件生产者)       │
└──────────┬───────────┘              └────────────┬───────────────┘
           │                                       │
           ▼                                       │
┌──────────────────────────────────────────────────────────────────┐
│                         QuoteStore                               │
│              (分布式状态管理 / JRaft 读写)                         │
└──────────┬───────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────────┐
│                      JRaft 共识层                                 │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              JraftStateMachine                            │    │
│  │  ┌─────────────────────────────────────────────────────┐ │    │
│  │  │                  QuoteEngine                         │ │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌──────────────────┐ │ │    │
│  │  │  │MarketQuote │ │MarketQuote │ │  MarketQuote ... │ │ │    │
│  │  │  │(ExchangeA) │ │(ExchangeB) │ │  (ExchangeN)     │ │ │    │
│  │  │  └────────────┘ └────────────┘ └──────────────────┘ │ │    │
│  │  └─────────────────────────────────────────────────────┘ │    │
│  └──────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
           ▲
           │ applyCmd
┌──────────┴───────────┐
│ OffchainEventConsumer│◄──── Kafka (offchain_event_v1)
│ (链上事件消费者)       │◄──── OffchainService (gRPC 回补)
└──────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────────┐
│                       持久化层                                    │
│  ┌──────────┐    ┌───────────┐    ┌──────────────┐              │
│  │  MySQL   │    │ DynamoDB  │    │   AWS S3     │              │
│  │ (K线/费率)│    │ (历史K线)  │    │ (快照存储)    │              │
│  └──────────┘    └───────────┘    └──────────────┘              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. 技术栈与依赖

| 组件 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.x | Web + JPA |
| RPC | gRPC + Protobuf | 服务间通信和客户端查询 |
| 序列化 | Google Protobuf | 数据序列化/反序列化 |
| 共识协议 | SOFAJRaft | 基于 Raft 的分布式一致性 |
| 消息队列 | Apache Kafka | 事件驱动架构 |
| 关系数据库 | MySQL + HikariCP | K线和资金费率持久化 |
| NoSQL | AWS DynamoDB | 历史K线和资金费率高性能查询 |
| 对象存储 | AWS S3 | 快照备份与恢复 |
| 注册中心 | Nacos | 服务发现与配置管理 |
| 监控 | OpenTelemetry | 指标采集 |

---

## 4. 项目结构

```
exchange.quote/
├── QuoteApplication.java                # 应用入口
├── QuoteException.java                  # 自定义异常
│
├── config/                              # 配置层
│   ├── QuoteConfiguration.java          # 行情相关 Bean 配置
│   ├── QuoteProperties.java             # 行情配置属性
│   ├── JraftConfiguration.java          # JRaft 配置
│   ├── JraftProperties.java             # JRaft 属性
│   └── DynamoConfiguration.java         # DynamoDB 客户端配置
│
├── controller/                          # REST API 控制器
│   ├── InternalController.java          # 内部运维接口
│   └── JraftController.java             # JRaft 集群管理接口
│
├── engine/                              # 核心引擎层
│   ├── QuoteEngine.java                 # 行情引擎（命令处理核心）
│   ├── QuoteCommand.java                # 命令封装（含异步Future）
│   └── data/                            # 内存数据容器
│       ├── MarketQuote.java             # 单个交易对的全部行情数据
│       ├── OrderContainer.java          # 订单簿容器
│       ├── DepthContainer.java          # 深度数据容器
│       ├── KlineContainer.java          # K线数据容器
│       ├── TickerContainer.java         # 24小时行情统计容器
│       ├── TicketContainer.java         # 最近成交记录容器
│       ├── FundingContainer.java        # 资金费率容器
│       ├── PriceContainer.java          # 外部价格容器
│       ├── PositionContainer.java       # 持仓容器
│       ├── MetaData.java                # 元数据（币种/交易对）
│       ├── FixKlineReqData.java         # K线修复请求数据
│       └── FixKlineChanged.java         # K线修复变更数据
│
├── model/                               # 数据模型层
│   ├── OrderModel.java                  # 订单模型
│   ├── KlineModel.java                  # K线模型
│   ├── KlineInterval.java              # K线周期枚举
│   ├── TickerModel.java                # 24小时行情统计模型
│   ├── TicketModel.java                # 成交记录模型
│   ├── DepthModel.java                 # 深度快照模型
│   ├── BookOrderModel.java             # 盘口档位模型
│   ├── BookTickerModel.java            # 最优买卖价模型
│   ├── PositionModel.java              # 仓位模型
│   ├── PriceModel.java                 # 价格档位模型（含订单链表）
│   ├── PriceModelList.java             # 排序价格档位列表
│   ├── ExchangeModel.java              # 交易对模型
│   └── CoinModel.java                  # 币种模型
│
├── service/                             # 服务层
│   ├── QuoteGrpcService.java           # gRPC 行情查询服务
│   ├── OffchainEventConsumer.java      # 链上事件 Kafka 消费者
│   ├── OffchainEventConsumerFactory.java # 消费者工厂
│   ├── OffchainEventManager.java       # 链上事件处理管理器
│   ├── OffchainService.java            # 链上事件 gRPC 回补服务
│   ├── QuoteEventProducer.java         # 行情变更事件 Kafka 生产者
│   ├── QuoteEventProducerFactory.java  # 生产者工厂
│   ├── QuoteStorageManager.java        # 持久化管理（MySQL/DynamoDB）
│   ├── QuoteStorageDbConsumer.java     # 行情存储消费者（K线持久化）
│   ├── QuoteEventStorageDbConsumer.java # 行情事件存储消费者
│   ├── SnapshotManager.java            # 快照管理（本地/S3）
│   ├── ExchangeSequencer.java          # 交易对顺序执行器
│   ├── KlineFixManager.java            # K线修复管理器
│   ├── OrderFixManager.java            # 订单修复管理器
│   └── QuoteMetrics.java               # 行情指标采集
│
├── jraft/                               # JRaft 共识层
│   ├── JraftStateMachine.java          # Raft 状态机
│   ├── JraftGroupServiceWrapper.java   # Raft 分组服务包装
│   ├── JraftLeaderTermLifeCycle.java   # Leader 任期生命周期接口
│   ├── JraftNameRegister.java          # 名称注册接口
│   └── JraftNacosNameRegister.java     # Nacos 服务注册实现
│
├── store/                               # 分布式状态存储层
│   ├── QuoteStore.java                 # 核心状态存储（JRaft读写）
│   ├── ExchangeStore.java              # 交易对存储
│   ├── ExchangeData.java               # 交易对快照数据
│   ├── DepthData.java                  # 深度快照数据
│   ├── KlineData.java                  # K线快照数据
│   └── CmdFutureClosure.java           # 命令Future回调
│
├── db/                                  # 数据库层
│   ├── entity/
│   │   ├── KlineEntity.java            # K线 JPA 实体
│   │   ├── FundingRateEntity.java      # 资金费率 JPA 实体
│   │   └── FundingRatePk.java          # 资金费率复合主键
│   └── repository/
│       ├── KlineRepository.java        # K线 JPA 仓库
│       └── FundingRateRepository.java  # 资金费率 JPA 仓库
│
├── dynamo/                              # DynamoDB 层
│   ├── KlineBean.java                  # K线 DynamoDB 映射
│   └── FundingRateBean.java            # 资金费率 DynamoDB 映射
│
└── util/                                # 工具层
    ├── Constants.java                   # 全局常量
    ├── ShardId.java                     # 分片ID计算
    ├── KlineId.java                     # K线ID生成
    ├── KlineTime.java                   # K线时间计算
    ├── SnowflakeIdGenerator.java        # 雪花ID生成器
    ├── DynamoUtil.java                  # DynamoDB 工具
    ├── DataPage.java                    # 分页结果封装
    ├── PageOffsetData.java              # 分页偏移量
    └── QuoteErrorKey.java               # 错误码常量
```

---

## 5. 行情处理核心流程

### 5.1 总体数据流

```
链上区块事件 ──► Kafka(offchain_event_v1) ──► OffchainEventConsumer
                                                     │
                                                     ▼
                                              OffchainEventManager
                                              (解析/按交易对分组)
                                                     │
                                                     ▼ applyQuoteSubmitCmd
                                              QuoteStore (JRaft共识)
                                                     │
                                                     ▼ onApply
                                              JraftStateMachine
                                                     │
                                                     ▼ processQuoteSubmitCmd
                                              QuoteEngine
                                                     │
                                           ┌─────────┼─────────┐
                                           ▼         ▼         ▼
                                      MarketQuote MarketQuote MarketQuote
                                      (交易对A)   (交易对B)   (交易对N)
                                           │
                                           ▼ processQuoteChangeCmd
                                      生成 QuoteEvent
                                           │
                                  ┌────────┼────────┐
                                  ▼                  ▼
                          Kafka(quote_event_v1)  持久化存储
                          (外部订阅)             (MySQL/DynamoDB)
```

### 5.2 事件消费流程

`OffchainEventConsumer` 是事件消费的入口，运行在独立的 `ConsumerThread` 线程中：

```
ConsumerThread.run()
  │
  ├── 1. trySeek(): 根据快照中保存的 Kafka offset 恢复消费位点
  │     └── 查询 QuoteStore 中的 messageOffsetMap
  │     └── 对每个分区执行 seek(offset + 1) 或 seekToBeginning
  │
  ├── 2. 循环 poll() Kafka 消息
  │     └── 解析 OffchainEventList (Protobuf)
  │     └── 对每个 OffchainEvent 调用 offchainEventManager.handleOffchainEvent()
  │     └── 异步提交 offset
  │
  ├── 3. tryWriteEndKline(): 每 60 秒触发一次 K 线结束
  │     └── 提交 EndKlineCmd 到 JRaft
  │
  └── 4. tryWriteDepth(): 每 200ms 触发一次深度快照写入
        └── 提交 WriteDepthCmd 到 JRaft
```

**链上事件类型** (`OffchainInternalEvent`)：

| 事件类型 | 说明 | 处理方法 |
|----------|------|----------|
| `COIN_UPDATE` | 币种信息更新 | `processCoinUpdate()` |
| `EXCHANGE_UPDATE` | 交易对信息更新 | `processExchangeUpdate()` |
| `ORDER_CREATE` | 新订单创建 | `processOrderCreate()` |
| `ORDER_CANCEL` | 订单撤销 | `processOrderCancel()` |
| `ORDER_FILL` | 订单成交 | `processOrderFill()` |
| `ORDER_TRIGGER` | 条件单触发 | `processOrderTrigger()` |
| `PRICE_TICKS` | 外部价格更新 (指数/预言机) | `processPriceTicks()` |
| `FUNDING_TICKS` | 资金费率更新 | `processFundingTicks()` |
| `FUNDING_INDICES` | 资金费用索引更新 | `processFundingIndices()` |
| `UPDATE_MESSAGE_OFFSET` | 消息偏移量更新 | `processMessageQueue()` |

### 5.3 JRaft 共识处理流程

```
QuoteSubmitCmd (提交命令)
        │
        ▼
QuoteStore.applyQuoteSubmitCmd()
        │
        ▼ doApplyTask (JRaft Task)
JraftStateMachine.onApply()
        │
        ▼
QuoteEngine.processQuoteSubmitCmd()
        │
        ├── OFFCHAIN_INTERNAL_EVENT_CMD ──► processOffchainEventCmd()
        │     └── 遍历每个 OffchainInternalEvent
        │     └── 根据事件类型分派到 MarketQuote 的对应处理方法
        │     └── 生成 QuoteChangeCmd (变更命令)
        │
        ├── END_KLINE_CMD ──► processEndKlineCmd()
        │     └── 计算分钟级全量K线时间
        │     └── 对所有 MarketQuote 执行 handleEndKline()
        │
        ├── WRITE_DEPTH_CMD ──► processWriteDepth()
        │     └── 对所有 MarketQuote 执行 handleDepth()
        │
        └── REMOVE_ORDERS_CMD ──► processRemoveOrdersCmd()
              └── 按价格删除指定交易对的订单
```

### 5.4 行情变更事件分发流程

`processQuoteChangeCmd()` 将处理后的变更转换为 `QuoteEvent` 并放入队列：

```
QuoteChangeCmd
    │
    ├── DepthChangeCmd       ──► QuoteEvent(DepthEvent)
    ├── BookTickerChangeCmd  ──► QuoteEvent(BookTickerEvent)
    ├── TickerChangeCmd      ──► QuoteEvent(TickerEvent)
    ├── TicketChangeCmd      ──► QuoteEvent(TicketEvent)
    ├── KlineChangeCmd       ──► QuoteEvent(KlineEvent, finished=true)
    ├── CurrentKlineChangeCmd──► QuoteEvent(KlineEvent, finished=false)
    ├── FundingChangeCmd     ──► QuoteEvent(FundingEvent)
    └── PriceChangeCmd       ──► QuoteEvent(PriceEvent)

QuoteEvent 放入 MarketQuote.quoteEventMap (ConcurrentSkipListMap)
    │
    └── isLeader 时通知 QuoteEventProducer
        └── 从 quoteEventMap 批量读取并发送到 Kafka(quote_event_v1)
```

---

## 6. 核心功能模块

### 6.1 订单簿管理 (OrderContainer)

**职责**：维护每个交易对的完整订单簿，包括活跃订单、条件单、买卖盘口。

**核心数据结构**：

```
OrderContainer
├── orderMap: Map<Long, OrderModel>          # orderId → 活跃订单
├── triggerOrderMap: Map<Long, OrderModel>    # orderId → 条件单
├── asks: PriceModelList (升序)               # 卖方价格档位列表
├── bids: PriceModelList (降序)               # 买方价格档位列表
├── depthContainer: DepthContainer            # 深度快照管理
└── bookTicker: BookTickerModel               # 当前最优买卖价
```

**PriceModelList 结构**（价格档位链表）：

```
PriceModelList
├── priceMap: TreeMap<Long, PriceModel>       # 按价格排序的档位
│     └── PriceModel
│         ├── price: long                     # 档位价格
│         ├── totalSize: long                 # 该档位总量
│         ├── orderNum: int                   # 该档位订单数
│         └── firstOrder → OrderModel ←→ OrderModel ←→ OrderModel  (双向链表)
└── bestPriceNode: PriceModel                 # 最优价格档位
```

**关键流程**：

1. **添加订单** (`addOrder`):
   - 判断是否上盘口（FOK/IOC/市价单不上盘口）
   - 买单加入 `bids`，卖单加入 `asks`
   - 更新 `depthContainer` 的价格变更

2. **成交处理** (`updateTakerOrderByFill` / `updateMakerOrderByFill`):
   - 扣减 taker/maker 的 `size`
   - 更新所在价格档位的 `totalSize`
   - 更新深度数据

3. **撤单处理** (`removeOrder`):
   - 从 `asks`/`bids` 中移除
   - 更新深度数据
   - 更新最优买卖价

4. **最优买卖价更新** (`tryUpdateBookTicker`):
   - 比较当前 asks/bids 的最优价格是否变化
   - 变化则更新 `bookTicker`

### 6.2 深度数据管理 (DepthContainer)

**职责**：基于订单簿实时数据维护深度快照，支持多档位深度输出。

**核心数据结构**：

```
DepthContainer
├── asks: TreeMap<BigDecimal, BigDecimal>      # 卖方: 价格 → 总量 (升序)
├── bids: TreeMap<BigDecimal, BigDecimal>      # 买方: 价格 → 总量 (降序)
├── baseDepthWrapper: DepthWrapper             # 基础深度快照 (200档)
├── subDepthWrapperMap: Map<Integer, DepthWrapper>  # 子深度快照 (15档)
├── currentOrderBookVersion: long              # 当前订单簿版本号
├── askPriceChanged: boolean                   # 卖方价格是否变更
└── bidPriceChanged: boolean                   # 买方价格是否变更
```

**关键流程**：

1. **价格变更通知** (`onPriceChanged`):
   - 当订单增减时被调用
   - 计算新的总量 = 原总量 - 旧size + 新size
   - 总量为 0 时自动删除档位
   - 递增 `currentOrderBookVersion`

2. **写入深度快照** (`writeToDepth`):
   - 每 200ms 触发一次
   - 从 `asks`/`bids` TreeMap 生成新的基础快照 (200档)
   - 通过 `getSubDepth()` 截取子深度 (15档)
   - 与旧快照做 diff，只输出变更部分（CHANGED 类型）

3. **深度 Diff 算法** (`diffDepth` / `diffBookOrderList`):
   - 双指针遍历新旧深度列表
   - 价格相同但量不同 → 输出新量
   - 旧有新无 → 输出量为 0 (表示删除)
   - 新有旧无 → 输出新档位

### 6.3 K线管理 (KlineContainer)

**职责**：维护三类价格的 K 线数据：成交价(LAST)、指数价(INDEX)、预言机价(ORACLE)。

**核心数据结构**：

```
KlineContainer
├── currentKlineMap: Map<KlineInterval, KlineModel>      # 当前未完成K线
├── currentFinishKlineMap: Map<KlineInterval, KlineModel> # 刚完成的K线 (用于广播)
├── lastKlineMap: Map<KlineInterval, KlineModel>          # 上一根完成的K线
├── klineMap: Map<KlineInterval, LRUMap<Long, KlineModel>> # 历史K线 (每周期最多1000根)
├── exchangeId: long
└── priceType: PriceType                                   # LAST / INDEX / ORACLE
```

**支持的 K 线周期**：

| 枚举 | 类型 | 间隔 |
|------|------|------|
| M1 | MINUTE_1 | 60,000ms |
| M5 | MINUTE_5 | 300,000ms |
| M15 | MINUTE_15 | 900,000ms |
| M30 | MINUTE_30 | 1,800,000ms |
| H1 | HOUR_1 | 3,600,000ms |
| H2 | HOUR_2 | 7,200,000ms |
| H4 | HOUR_4 | 14,400,000ms |
| H6 | HOUR_6 | 21,600,000ms |
| H8 | HOUR_8 | 28,800,000ms |
| H12 | HOUR_12 | 43,200,000ms |
| D1 | DAY_1 | 86,400,000ms |
| W1 | WEEK_1 | 604,800,000ms |
| MON1 | MONTH_1 | ~2,678,400,000ms |

**关键流程**：

1. **K 线累积** (`computeKline`):
   ```
   每笔成交(Ticket) → accumulateKline()
     └── 遍历所有周期
         └── 获取/创建当前K线
         └── 首次设置 open = price
         └── 更新 close = price
         └── 更新 high = max(high, price)
         └── 更新 low = min(low, price)
         └── 累加 trades, size, value
         └── 如果是卖方成交: 累加 makerBuySize, makerBuyValue
   ```

2. **K 线完成** (`finishKline`):
   ```
   触发条件: ticket.time >= currentKlineTime + M1.interval
     或 OffchainEventConsumer 每60秒定时触发 EndKlineCmd

   处理逻辑:
     1. 完成1分钟K线 (finishMinKline)
     2. 检查其他周期是否也需要完成 (finishOtherKline)
        - 对于 D1/W1/MON1: 使用UTC时间边界判断
        - 对于其他周期: klineTime % interval == 0 且跨度 >= interval
     3. 当前K线 → lastKline
     4. 创建新的当前K线，沿用上一周期收盘价作为开高低收
   ```

3. **K 线修复** (`fixKline`):
   - 支持修复历史和当前 K 线的 OHLCV 数据
   - 修复值会向更大周期传递（累加 size/value/trades 差值，更新高低价）

### 6.4 24小时行情统计 (TickerContainer)

**职责**：维护滚动 24 小时窗口内的行情统计数据。

**核心数据结构**：

```
TickerContainer
├── ticker: TickerModel                       # 当前行情统计
├── baseInterval: KlineInterval = M15         # 滚动基准周期 (15分钟)
├── highNodeList: LinkedList<PriceNode>       # 高价单调递减队列
├── lowNodeList: LinkedList<PriceNode>        # 低价单调递增队列
└── klineList: LinkedList<KlineModel>         # 15分钟K线列表 (窗口内)
```

**关键流程**：

1. **实时更新** (`computeTicker`):
   - 每笔成交调用
   - 维护高价/低价单调队列（O(1) 获取窗口内最高/最低价）
   - 更新 open/close/high/low
   - 累加 trades/size/value
   - 计算涨跌幅 = (close - open) / open

2. **滚动窗口** (`rollingTicker`):
   - 每完成一根 M15 K 线时触发
   - 窗口超过 24 小时时：
     - 移除最早的 M15 K 线
     - 从单调队列中移除过期价格节点
     - 扣减对应的 trades/size/value
     - 更新 open 为移除K线的 close 价

3. **单调队列算法** (`handlePriceNodeList`):
   ```
   高价队列 (降序): 从尾部移除所有 <= 新价格的节点，然后添加
   低价队列 (升序): 从尾部移除所有 >= 新价格的节点，然后添加

   → 队首始终是窗口内的最高/最低价
   → 滚动时从队首移除过期节点
   ```

### 6.5 最近成交管理 (TicketContainer)

**职责**：维护最近的成交记录缓存。

**核心数据结构**：

```
TicketContainer
├── exchange: ExchangeModel
└── ticketMap: ConcurrentLinkedHashMap<String, TicketModel>  # LRU 缓存, 最多1000条
```

**成交ID生成规则**：
```
ticketId = blockHeight + 零填充eventSeqInBlock(8位) + index(3位)
例: 12345600000001001
```

### 6.6 资金费率管理 (FundingContainer)

**职责**：维护永续合约的实时资金费率和资金费用。

**核心数据结构**：

```
FundingContainer
├── exchange: ExchangeModel
├── currentFundingRate: FundingRate     # 当前资金费率
└── currentFundingFee: FundingFee       # 当前资金费用
```

**FundingRate 字段** (来自链上 FundingTick 转换):

| 字段 | 说明 |
|------|------|
| exchangeId | 交易对ID |
| fundingTime | 资金费时间 |
| fundingCalculateTime | 资金费率计算时间 |
| fundingRate | 当前资金费率 |
| forecastFundingRate | 预测资金费率 |
| previousFundingRate | 上期资金费率 |
| oraclePrice | 预言机价格 |
| indexPrice | 指数价格 |
| isSettlement | 是否结算 |
| premiumIndex | 溢价指数 |
| avgPremiumIndex | 平均溢价指数 |
| impactMarginNotional | 冲击保证金名义值 |
| impactAskPrice | 冲击卖价 |
| impactBidPrice | 冲击买价 |
| interestRate | 利率 |
| predictedFundingRate | 预估资金费率 |
| fundingRateIntervalMinutes | 资金费率间隔 (分钟) |

**FundingFee 字段** (来自链上 FundingIndex 转换):

| 字段 | 说明 |
|------|------|
| exchangeId | 交易对ID |
| fundingTime | 资金费时间 |
| fundingFee | 资金费用 |

### 6.7 外部价格管理 (PriceContainer)

**职责**：维护外部喂价数据（指数价格、预言机价格等）。

**核心数据结构**：

```
PriceContainer
├── exchange: ExchangeModel
└── priceMap: Map<PriceType, Price>    # 按价格类型存储
```

**Price 字段**：

| 字段 | 说明 |
|------|------|
| exchangeId | 交易对ID |
| priceType | 价格类型 (INDEX/ORACLE) |
| price | 价格值 |
| createdTime | 创建时间 |

### 6.8 仓位管理 (PositionContainer)

**职责**：维护所有子账户在某交易对下的持仓情况，用于计算持仓量 (Open Interest)。

**核心数据结构**：

```
PositionContainer
├── exchange: ExchangeModel
├── positionMap: Map<String, PositionModel>   # positionKey → 仓位
│     └── positionKey = "subaccountId_exchangeId_marginMode"
└── totalPosition: TotalPosition
      ├── longSize: BigDecimal                # 全部多仓总量
      └── shortSize: BigDecimal               # 全部空仓总量
```

**关键流程**：

1. **开仓** (`onPositionOpen`):
   - 买入开多: `openSize += tradeSize`, `openValue += tradeSize * tradePrice`
   - 卖出开空: `openSize -= tradeSize`, `openValue -= tradeSize * tradePrice`

2. **平仓** (`onPositionClose`):
   - 买入平空: 如果 `tradeSize > |openSize|`，先平空后开多 (仓位反转)
   - 卖出平多: 如果 `tradeSize > openSize`，先平多后开空 (仓位反转)
   - 部分平仓: 按比例计算 `deltaOpenValue`

3. **总持仓量**: `totalPositionSize = min(longSize, shortSize)` (多空取小)

4. **仅永续合约更新仓位**，且排除自成交（taker 和 maker 是同一 subaccount）

### 6.9 元数据管理 (MetaData)

**职责**：维护币种和交易对的基础信息。

```
MetaData
├── coinMap: Map<Long, Coin>                  # coinId → Coin (Protobuf)
└── exchangeMap: Map<Long, ExchangeModel>     # exchangeId → ExchangeModel
```

---

## 7. 数据模型详解

### 7.1 OrderModel - 订单模型

```java
OrderModel {
    // 链表指针 (transient, 不序列化)
    PriceModel head;         // 所属价格档位
    OrderModel next;         // 下一个订单 (同价格)
    OrderModel prev;         // 上一个订单 (同价格)

    // 订单属性
    long orderId;            // 订单ID
    long exchangeId;         // 交易对ID
    long coinId;             // 币种ID
    long subaccountId;       // 子账户ID
    long orderBookVersion;   // 订单簿版本
    long price;              // 价格 (链上 long 值, 需转换)
    long size;               // 数量 (链上 long 值, 需转换)
    MarginMode marginMode;   // 保证金模式 (CROSS/ISOLATED)
    boolean isBuy;           // 是否买单
    boolean isOnBook;        // 是否挂单上盘口
    TimeInForce timeInForce; // 有效期类型 (GTC/IOC/FOK)
    TriggerType triggerType; // 条件单类型
}
```

**上盘口规则**: `isOnBook = !(isFOK || isIOC || size==0 || price==0)`

### 7.2 KlineModel - K线模型

```java
KlineModel {
    long id;                     // K线ID (由 KlineId.generate 生成)
    long time;                   // K线起始时间 (ms)
    KlineInterval interval;      // K线周期
    PriceType priceType;         // 价格类型 (LAST/INDEX/ORACLE)
    long exchangeId;             // 交易对ID
    long trades;                 // 成交笔数
    BigDecimal open;             // 开盘价
    BigDecimal close;            // 收盘价
    BigDecimal high;             // 最高价
    BigDecimal low;              // 最低价
    BigDecimal size;             // 成交量
    BigDecimal value;            // 成交额
    BigDecimal makerBuySize;     // 主动卖出成交量 (maker=buyer)
    BigDecimal makerBuyValue;    // 主动卖出成交额
}
```

**K线ID生成规则** (`KlineId.generate`):
- 组合 exchangeId + priceType + klineType + klineTime 生成唯一 long ID

### 7.3 TickerModel - 行情统计模型

```java
TickerModel {
    long exchangeId;                 // 交易对ID
    long trades;                     // 24h 成交笔数
    BigDecimal priceChange;          // 24h 价格变化
    BigDecimal priceChangePercent;   // 24h 价格变化百分比 (6位小数)
    BigDecimal size;                 // 24h 成交量
    BigDecimal value;                // 24h 成交额
    BigDecimal high;                 // 24h 最高价
    BigDecimal low;                  // 24h 最低价
    BigDecimal open;                 // 24h 开盘价
    BigDecimal close;                // 24h 收盘价 (最新价)
    long highTime;                   // 最高价时间
    long lowTime;                    // 最低价时间
    long startTime;                  // 窗口起始时间
    long endTime;                    // 窗口结束时间
    BigDecimal lastPrice;            // 最新成交价
    BigDecimal markPrice;            // 标记价格
    BigDecimal indexPrice;           // 指数价格
    BigDecimal oraclePrice;          // 预言机价格
    BigDecimal openInterest;         // 未平仓合约量
    BigDecimal fundingRate;          // 当前资金费率
    long fundingTime;                // 当前资金费时间
    long nextFundingTime;            // 下次资金费时间
}
```

**24小时判断**: `endTime - startTime > 86,400,000ms`

### 7.4 TicketModel - 成交记录模型

```java
TicketModel {
    String id;                   // 成交ID (blockHeight + eventSeq + index)
    long time;                   // 成交时间 (区块时间)
    BigDecimal price;            // 成交价格
    BigDecimal size;             // 成交数量
    BigDecimal value;            // 成交金额
    long takerOrderId;           // Taker 订单ID
    long makerOrderId;           // Maker 订单ID
    long takerAccountId;         // Taker 子账户ID
    long makerAccountId;         // Maker 子账户ID
    long exchangeId;             // 交易对ID
    boolean bestMatch;           // 是否最优匹配
    boolean buyerMaker;          // Maker是否为买方
    String txHash;               // 交易哈希
}
```

### 7.5 DepthModel - 深度快照模型

```java
DepthModel {
    long startVersion;           // 起始版本号
    long endVersion;             // 结束版本号
    int level;                   // 深度档位数 (200 或 15)
    long exchangeId;             // 交易对ID
    int scale;                   // 精度 (默认18)
    byte depthType;              // 类型: SNAPSHOT / CHANGED
    List<BookOrderModel> asks;   // 卖方深度列表
    List<BookOrderModel> bids;   // 买方深度列表
}
```

### 7.6 BookOrderModel - 盘口档位模型

```java
BookOrderModel {
    BigDecimal price;            // 价格
    BigDecimal size;             // 该档位总量
}
```

### 7.7 BookTickerModel - 最优买卖价模型

```java
BookTickerModel {
    long exchangeId;             // 交易对ID
    BigDecimal bestAskPrice;     // 最优卖价
    BigDecimal bestAskSize;      // 最优卖量
    BigDecimal bestBidPrice;     // 最优买价
    BigDecimal bestBidSize;      // 最优买量
    long time;                   // 更新时间
}
```

### 7.8 PositionModel - 仓位模型

```java
PositionModel {
    long subaccountId;           // 子账户ID
    long exchangeId;             // 交易对ID
    MarginMode marginMode;       // 保证金模式
    BigDecimal openSize;         // 持仓量 (正=多, 负=空)
    BigDecimal openValue;        // 持仓价值 (正=多, 负=空)

    // positionKey = "subaccountId_exchangeId_marginMode"

    // SizeDelta 内部类 (开平仓变化量)
    static class SizeDelta {
        BigDecimal longSize;     // 多仓变化量
        BigDecimal shortSize;    // 空仓变化量
    }
}
```

### 7.9 PriceModel - 价格档位模型

```java
PriceModel {
    long price;                  // 价格 (链上 long 值)
    long totalSize;              // 该价格总量
    int orderNum;                // 该价格订单数量
    OrderModel firstOrder;       // 订单链表头 (双向链表)
    OrderModel lastOrder;        // 订单链表尾
}
```

### 7.10 ExchangeModel - 交易对模型

```java
ExchangeModel {
    Exchange exchange;           // 原始 Protobuf 交易对信息
    Coin baseCoin;               // 基础币种
    Coin quoteCoin;              // 计价币种

    // 转换方法 (链上 long → BigDecimal)
    BigDecimal transformPrice(long price);     // 价格转换
    BigDecimal transformSize(long size);       // 数量转换
    BigDecimal transformValue(long value);     // 金额转换
    BigDecimal transformRate(long ppm);        // 费率转换 (PPM → 小数)
    BigDecimal transformFundingFee(long fee);  // 资金费转换
    BigDecimal divideToPrice(BigDecimal value, BigDecimal size);  // 金额/数量=价格
    long toLongPrice(BigDecimal price);        // BigDecimal → long
    BigDecimal getQuoteCoinStepSize();         // 计价币最小步长
}
```

### 7.11 KlineInterval - K线周期枚举

| 枚举值 | KlineType | 间隔(ms) | 说明 |
|--------|-----------|----------|------|
| `M1` | MINUTE_1 | 60,000 | 1分钟 |
| `M5` | MINUTE_5 | 300,000 | 5分钟 |
| `M15` | MINUTE_15 | 900,000 | 15分钟 |
| `M30` | MINUTE_30 | 1,800,000 | 30分钟 |
| `H1` | HOUR_1 | 3,600,000 | 1小时 |
| `H2` | HOUR_2 | 7,200,000 | 2小时 |
| `H4` | HOUR_4 | 14,400,000 | 4小时 |
| `H6` | HOUR_6 | 21,600,000 | 6小时 |
| `H8` | HOUR_8 | 28,800,000 | 8小时 |
| `H12` | HOUR_12 | 43,200,000 | 12小时 |
| `D1` | DAY_1 | 86,400,000 | 1天 |
| `W1` | WEEK_1 | 604,800,000 | 1周 |
| `MON1` | MONTH_1 | 2,678,400,000 | 1月 |

---

## 8. gRPC 服务接口

`QuoteGrpcService` 提供以下 gRPC 查询接口：

| 方法 | 请求 | 响应 | 说明 |
|------|------|------|------|
| `getBookTicker` | GetBookTickerRequest | GetBookTickerResponse | 获取最优买卖价（支持批量） |
| `getDepth` | GetDepthRequest | GetDepthResponse | 获取指定交易对的深度数据 |
| `getKline` | GetKlineRequest | GetKlineResponse | 获取内存中的 K 线数据 |
| `getHistoryKlinePage` | GetHistoryKlinePageRequest | GetHistoryKlinePageResponse | 分页查询历史 K 线 |
| `getTicker` | GetTickerRequest | GetTickerResponse | 获取 24h 行情统计（支持批量） |
| `getTicket` | GetTicketRequest | GetTicketResponse | 获取最近成交记录 |
| `getFundingRatePage` | GetFundingRatePageRequest | GetFundingRatePageResponse | 分页查询资金费率历史 |
| `getOpenInterest` | GetOpenInterestRequest | GetOpenInterestResponse | 获取持仓量 (当前为空实现) |

**读一致性保证**：

- 所有查询先通过 `quoteStore.getMarketQuoteMapSafe(shardId)` 获取数据
- 底层使用 JRaft 的 `readIndex` 确保线性一致性读
- 批量查询按 shardId 分组，每组独立走 JRaft 读

---

## 9. 分布式架构

### 9.1 JRaft 共识层

**核心组件**：

- `JraftStateMachine`: 状态机实现，处理 `onApply()` 回调
- `JraftGroupServiceWrapper`: 管理多个 JRaft 分组
- `QuoteStore`: 分布式状态读写入口

**命令类型** (`QuoteJraftCmd`):

| 命令 | 说明 |
|------|------|
| `QuoteSubmitCmd` | 提交行情数据变更 |
| `QuoteChangeCmd` | 行情变更结果 |
| `RemoveQuoteEventQueueCmd` | 清理已消费的事件队列 |
| `GetQuoteSnapshotCmd` | 获取快照 |
| `LoadQuoteSnapshotCmd` | 加载快照 |

**Leader 任期管理**：

- `JraftLeaderTermLifeCycle` 接口用于生命周期回调
- `OffchainEventConsumer`: 仅 Leader 节点消费 Kafka 和处理事件
- `QuoteEventProducer`: 仅 Leader 节点向 Kafka 生产行情事件

### 9.2 分片策略

```java
// ShardId.java
public static int getShardId(long exchangeId) {
    return (int)(exchangeId % maxShardNum);  // 默认 maxShardNum = 1
}
```

- 默认单分片模式 (`maxShardNum = 1`)
- 最多支持 16 个分片 (`MAX_SHARD_NUM = 16`)
- 每个分片有独立的 JRaft 分组和 QuoteEngine 实例
- 同一分片内的所有交易对共享同一个 Raft 共识

### 9.3 快照管理

**快照内容** (`QuoteSnapshot`):

```
QuoteSnapshot
├── exchangeDataList           # 各交易对的完整状态
│   └── ExchangeData
│       ├── orders             # 活跃订单列表
│       ├── triggerOrders      # 条件单列表
│       ├── depthData          # 深度快照
│       ├── tradeKlineData     # 成交价K线数据
│       ├── markKlineData      # 指数价K线数据
│       ├── oracleKlineData    # 预言机价K线数据
│       ├── tickets            # 最近成交记录
│       ├── ticker             # 24h行情统计
│       ├── bookTicker         # 最优买卖价
│       ├── positionData       # 持仓数据
│       ├── fundingRate        # 当前资金费率
│       ├── fundingFee         # 当前资金费用
│       └── prices             # 外部价格
├── messageOffsetList          # Kafka 消费位点
├── metaData                   # 元数据 (币种/交易对)
└── lastOffchainHeader         # 最后处理的链上区块头
```

**快照流程**：

1. JRaft 触发 `onSnapshotSave` → `SnapshotManager.saveSnapshot()`
2. 将 `QuoteSnapshot` 序列化为 Protobuf 二进制 + JSON (调试)
3. 保存到本地文件系统
4. 配置 `saveSnapshotToS3=true` 时同步上传 S3

**快照恢复**：

1. 通过 `InternalController.loadSnapshotFromS3()` API 触发
2. 从 S3 下载快照文件
3. 反序列化为 `QuoteSnapshot`
4. 调用 `QuoteEngine.importSnapshot()` 恢复状态
5. 可选重置 Kafka 消费位点

---

## 10. 持久化层

### 10.1 MySQL 数据库表结构

#### t_kline (K线历史数据)

| 字段 | 类型 | 说明 |
|------|------|------|
| `kline_id` | BIGINT (PK) | K线ID |
| `exchange_id` | BIGINT | 交易对ID |
| `kline_type` | INT | K线类型 |
| `kline_time` | BIGINT | K线时间 (ms) |
| `price_type` | INT | 价格类型 |
| `trades` | BIGINT | 成交笔数 |
| `size` | VARCHAR(255) | 成交量 |
| `value` | VARCHAR(255) | 成交额 |
| `high` | VARCHAR(255) | 最高价 |
| `low` | VARCHAR(255) | 最低价 |
| `open` | VARCHAR(255) | 开盘价 |
| `close` | VARCHAR(255) | 收盘价 |
| `maker_buy_size` | VARCHAR(255) | Maker买入量 |
| `maker_buy_value` | VARCHAR(255) | Maker买入额 |

**索引**: `idx_exchange_id_kline_type_price_type_kline_time (exchange_id, kline_type, price_type, kline_time)`

#### t_funding_rate (资金费率历史)

| 字段 | 类型 | 说明 |
|------|------|------|
| `exchange_id` | BIGINT (PK) | 交易对ID |
| `funding_calculate_time` | BIGINT (PK) | 计算时间 |
| `premium_index_calculate_time` | BIGINT (PK) | 溢价指数计算时间 |
| `funding_time` | BIGINT | 资金费时间 |
| `funding_rate` | VARCHAR(255) | 资金费率 |
| `oracle_price` | VARCHAR(255) | 预言机价格 |
| `index_price` | VARCHAR(255) | 指数价格 |
| `is_settlement` | BIT(1) | 是否结算 |
| `forecast_funding_rate` | VARCHAR(255) | 预测资金费率 |
| `previous_funding_rate` | VARCHAR(255) | 上期资金费率 |
| `previous_funding_calculate_time` | BIGINT | 上期计算时间 |
| `premium_index` | VARCHAR(255) | 溢价指数 |
| `avg_premium_index` | VARCHAR(255) | 平均溢价指数 |
| `impact_margin_notional` | VARCHAR(255) | 冲击保证金名义值 |
| `impact_ask_price` | VARCHAR(255) | 冲击卖价 |
| `impact_bid_price` | VARCHAR(255) | 冲击买价 |
| `interest_rate` | VARCHAR(255) | 利率 |
| `predicted_funding_rate` | VARCHAR(255) | 预估资金费率 |
| `funding_rate_interval_minutes` | BIGINT | 费率间隔 (分钟) |

### 10.2 DynamoDB 表结构

通过 `DynamoConfiguration` 配置，使用 AWS DynamoDB Enhanced Client：

- **KlineBean**: K线历史数据的 DynamoDB 映射
- **FundingRateBean**: 资金费率历史的 DynamoDB 映射

---

## 11. 消息队列

### Kafka Topic

| Topic | 方向 | 格式 | 说明 |
|-------|------|------|------|
| `offchain_event_v1` | 消费 | OffchainEventList (Protobuf) | 链上事件输入 |
| `quote_event_v1` | 生产 | QuoteEvent (Protobuf) | 行情变更输出 |

### 消费者配置

- Consumer Group: `quote-server-{shardId}`
- 支持按分片独立消费
- 消费位点保存在 JRaft 快照中，启动时恢复

### 生产者配置

- 仅 Leader 节点生产消息
- 从 `MarketQuote.quoteEventMap` 批量读取
- 生产完成后通知 JRaft 清理已消费事件

---

## 12. 配置说明

### application.yml 关键配置

```yaml
spring:
  application:
    name: quote-server
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: quote-server

# 行情服务配置
quote:
  offchain-event-topic: offchain_event_v1
  quote-event-topic: quote_event_v1
  save-snapshot-to-s3: false
  s3:
    bucket: quote-server-snapshot
    region: ap-southeast-1

# gRPC 配置
grpc:
  server:
    port: ${grpc-port}
  client:
    offchain-server:
      negotiation-type: plaintext
      enable-keep-alive: true
      keep-alive-time: 30s
```

---

## 13. 关键常量

```java
// Constants.java
MAX_DEPTH_LEVEL = 200;       // 最大深度档位数
MAX_DEPTH_SCALE = 18;        // 最大深度精度
MAX_SHARD_NUM = 16;          // 最大分片数

// KlineContainer
MAX_KLINE_SIZE = 1000;       // 每周期最多缓存K线数

// TicketContainer
MAX_TICKET_SIZE = 1000;      // 最多缓存成交记录数

// DepthContainer
SUB_DEPTH_LEVEL = 15;        // 子深度档位数

// OffchainEventConsumer
DEPTH_WRITE_INTERVAL = 200ms;    // 深度写入间隔
KLINE_END_INTERVAL = 60000ms;    // K线结束检查间隔

// OffchainService
BATCH_SIZE = 1000;           // 回补事件批次大小
```
