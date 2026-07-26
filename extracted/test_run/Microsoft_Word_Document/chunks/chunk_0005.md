# Chunk 5 of 6
# Source: Microsoft_Word_Document/Microsoft_Word_Document.md
# Pos range: 382 - 397
# Section: 5.  数据模型

# POS: 382 | TYPE: paragraph
5.  数据模型

# POS: 383 | TYPE: paragraph
本章描述网关平台涉及的核心数据实体及其关系，为数据库设计和接口开发提供数据层面的参考。平台的数据存储分为配置数据、运行 时数据 和遥测数据三类，分别采用不同的存储技术和一致性模型。

# POS: 384 | TYPE: paragraph
5.1  配置数据模型

# POS: 385 | TYPE: paragraph
配置数据是平台的核心资产，包括路由规则、认证策略、限流策略、熔断策略、灰度策略等。这些数据具有以下特征：读多写少、变更频率低但要求强一致性、需要版本历史和审计追踪。因此，配置数据存储在   etcd   中，利用其  Raft  共识算法保证强一致性，利用  Watch  机制实现配置变更的实时通知。

# POS: 386 | TYPE: paragraph
配置数据在   etcd   中以层次化的键值对形式存储。键的命名遵循  /gateway/{cluster}/{resource-type}/{resource-id}  的规范，例如  /gateway/prod/routes/rt-7d4e9f2a  表示生产集群中  ID  为  rt-7d4e9f2a  的路由规则。值为  JSON  格式的配置对象，包含完整的资源定义和元数据。每次配置变更时，系统会生成一个新的版本键  /gateway/{cluster}/{resource-type}/{resource-id}/versions/{version-seq} ，将变更前的完整配置快照写入该键，同时更新主键的值为最新配置。版本序列 号采用 单调递增的整数，便于快速定位和回滚。

# POS: 387 | TYPE: paragraph
配置数据在控制 面内部 还会被转换为   xDS   资源格式，存储在内存缓存中供  Envoy  订阅。这种双层存储结构（ etcd   持久层  +  内存缓存层）既保证了数据的持久性和一致性，又满足了数据面高频读取的性能需求。内存缓存通过   etcd  Watch  机制与持久层保持同步，同步延迟不超过一百毫秒。

# POS: 388 | TYPE: paragraph
5.2  运行时数据模型

# POS: 389 | TYPE: paragraph
运行 时数据 是平台在运行过程中产生的临时状态信息，包括限流计数器、熔断器状态、会话信息、缓存数据等。这些数据具有以下特征：高频读写、允许短暂不一致、有明确的过期时间。因此，运行 时数据 存储在  Redis Cluster  中，利用其高性能的内存读写能力和丰富的数据结构支持。

# POS: 390 | TYPE: paragraph
限流计数器采用  Redis  的  String  类型配合  EXPIRE  命令实现滑动窗口限流。每个计数器的键名为   ratelimit :{policy-id}:{identity}:{time-window} ，其中  identity  根据策略作用域可能是用户  ID 、 IP  地址或全局标识。值为当前时间窗口内的请求计数，配合  TTL  实现自动过期。对于令牌桶算法，每个策略对应一个  Redis Hash ，包含  tokens （当前令牌数）、 last_update （上次更新时间）和  capacity （桶容量）三个字段，通过  Lua  脚本保证令牌扣减和补充的原子性。

# POS: 391 | TYPE: paragraph
熔断器状态采用  Redis  的  String  类型存储，键名为   circuitbreaker:{ policy- id} ， 值为  JSON  对象，包含  state （当前状态： closed/open/half-open ）、 failure_count （当前失败计数）、 last_failure_time （上次失败时间）和 success_count （ 半开状态下的成功计数）等字段 。 熔断器状态由控制面的策略引擎根据后端服务的健康检查结果实时更新， Envoy  通过定期轮询或订阅机制获取最新状态。

# POS: 392 | TYPE: paragraph
会话信息（如  OAuth 2.0  的  Access Token  缓存）采用  Redis  的  Hash  类型存储，键名为   session:{ token- hash} ， 包含  user_id 、 roles 、 expires_at  等字段， TTL  设置为  Token  的过期时间 。 缓存命中时直接从  Redis  读取，避免每次都向认证中心发起验证请求；缓存未命中或过期时，再回源到认证中心验证并写入缓存。

# POS: 393 | TYPE: paragraph
5.3  遥测数据模型

# POS: 394 | TYPE: paragraph
遥测数据包括分布式追踪、指标和日志三类，分别存储在不同的后端系统中，但通过  Trace ID  进行关联，形成统一的可观测性视图。

# POS: 395 | TYPE: paragraph
分布式追踪数据以  Span  为基本单位，每个  Span  包含  trace_id 、 span_id 、 parent_span_id 、 service_name 、 operation_name 、 start_time 、 duration 、 tags （键值对标签）和  logs （时间戳事件列表）等字段。 Span  数据通过  OpenTelemetry Collector  接收后，经批处理和采样过滤，写入  Jaeger  的  Cassandra  或  Elasticsearch  后端。存储格式采用  Jaeger  的原生  Thrift  或  OTLP  格式，支持高效的按  Trace ID  查询和按服务名聚合。

# POS: 396 | TYPE: paragraph
指标数据以时间序列形式存储在  Prometheus  中，每个时间序列由指标名称和一组标签唯一标识。网关平台暴露的核心指标包括： gateway_requests_total （按路由、方法、状态码聚合的请求总数）、 gateway_request_duration_seconds （按路由聚合的请求延迟直方图）、 gateway_active_connections （当前活跃连接数）、 gateway_rate_limit_hits （限流触发次数）、 gateway_circuit_breaker_state （熔断器状态， 0=closed, 1=open, 2=half-open ）等。指标保留期限为十五天，通过  Thanos  或  Cortex  实现长期存储和跨集群聚合。

# POS: 397 | TYPE: paragraph
日志数据以结构化  JSON  格式写入  Elasticsearch ，每个日志文档包含  timestamp 、 level 、 component 、 trace_id 、 span_id 、 message  和自定义字段。访问日志作为特殊的日志类型，包含额外的  http  相关字段（ method 、 path 、 status_code 、 latency_ms 、 client_ip 、 user_agent 、 route_name 、 destination_service   等）。日志保留期限为三十天，超过后自动迁移到对象存储（ S3/ MinIO ）进行冷备， 冷备数据 保留一年，可通过专用工具按需恢复查询。

