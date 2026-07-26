# Chunk 6 of 6
# Source: Microsoft_Word_Document/Microsoft_Word_Document.md
# Pos range: 398 - 412
# Section: 6.  附录

# POS: 398 | TYPE: paragraph
6.  附录

# POS: 399 | TYPE: paragraph
附录 A HTTP  状态码定义

# POS: 400 | TYPE: paragraph
平台所有  API  接口遵循统一的  HTTP  状态码规范。状态 码分为平台级状态码和业务级状态码 两个层次： HTTP  状态码表示请求在传输层面的结果，业务状态码（ code  字段）表示业务逻辑层面的结果。

# POS: 401 | TYPE: paragraph
HTTP  状态码定义如下。 200 OK  表示请求已成功处理，响应体中包含业务数据。 400 Bad Request  表示请求参数有误或格式不正确，例如缺少必填字段、字段类型不匹配、 JSON  格式解析失败等，响应体中包含具体的参数错误信息。 401 Unauthorized  表示请求未携带有效的认证凭证，或凭证已过期，客户端需要重新获取  Access Token  或  AK/SK  签名后重试。 403 Forbidden  表示请求已通过认证，但当前身份不具备访问该资源的权限，例如普通服务开发者尝试操作全局配置。 404 Not Found  表示请求的资源不存在，例如查询一个已被删除的路由规则。 409 Conflict  表示请求与当前状态冲突，例如创建同名路由规则、在灰度发布进行中时尝试删除基线版本。 429 Too Many Requests  表示请求触发了限流策略，响应头中包含重试等待时间。 500 Internal Server Error  表示服务器内部错误，通常是未预期的异常，需要联系平台管理员排查。 503 Service Unavailable  表示网关或后端服务暂时 不 可用，可能由于熔断器打开、后端实例全部不健康或网关自身过载。

# POS: 402 | TYPE: paragraph
附录 B  错误码对照表

# POS: 403 | TYPE: paragraph
业务级 错误码（ code  字段）定义如下：

# POS: 404 | TYPE: table
| 错误码 | 错误名称 | HTTP 状态码 | 说明 |
| --- | --- | --- | --- |
| 0 | SUCCESS | 200 | 操作成功 |
| 1001 | INVALID_PARAMETER | 400 | 请求参数无效 |
| 1002 | MISSING_PARAMETER | 400 | 缺少必填参数 |
| 1003 | INVALID_JSON | 400 | 请求体 JSON 格式错误 |
| 1004 | INVALID_FORMAT | 400 | 字段格式不符合要求 |
| 2001 | UNAUTHORIZED | 401 | 未提供认证凭证或凭证无效 |
| 2002 | TOKEN_EXPIRED | 401 | Access Token 已过期 |
| 2003 | SIGNATURE_INVALID | 401 | AK/SK 签名验证失败 |
| 2004 | TOKEN_REVOKED | 401 | Token 已被撤销 |
| 3001 | FORBIDDEN | 403 | 无权访问该资源 |
| 3002 | RATE_LIMITED | 429 | 请求频率超过限制 |
| 3003 | IP_BLOCKED | 403 | IP 地址被黑名单拦截 |
| 4001 | NOT_FOUND | 404 | 资源不存在 |
| 4002 | ROUTE_NOT_FOUND | 404 | 路由规则不存在 |
| 4003 | POLICY_NOT_FOUND | 404 | 策略不存在 |
| 5001 | CONFLICT | 409 | 资源冲突 |
| 5002 | DUPLICATE_NAME | 409 | 名称已存在 |
| 5003 | CANARY_IN_PROGRESS | 409 | 灰度发布进行中，无法修改基线 |
| 6001 | INTERNAL_ERROR | 500 | 服务器内部错误 |
| 6002 | CONFIG_SYNC_FAILED | 500 | 配置同步失败 |
| 6003 | ETCD_UNAVAILABLE | 503 | 配置存储服务不可用 |
| 6004 | REDIS_UNAVAILABLE | 503 | 运行时存储服务不可用 |
| 7001 | SERVICE_UNAVAILABLE | 503 | 后端服务不可用 |
| 7002 | CIRCUIT_BREAKER_OPEN | 503 | 熔断器已打开 |
| 7003 | TIMEOUT | 504 | 请求超时 |

# POS: 405 | TYPE: paragraph
附录 C API  接口汇总

# POS: 406 | TYPE: paragraph
本文档定义的全部  API  接口汇总如下：

# POS: 407 | TYPE: table
| 接口 | 方法 | 路径 | 功能域 | 说明 |
| --- | --- | --- | --- | --- |
| 创建路由规则 | POST | /api/v1/gateway/routes | 路由管理 | 创建新的路由规则 |
| 查询路由列表 | GET | /api/v1/gateway/routes | 路由管理 | 分页查询路由规则 |
| 更新路由规则 | PUT | /api/v1/gateway/routes/{route_id} | 路由管理 | 更新指定路由规则 |
| 删除路由规则 | DELETE | /api/v1/gateway/routes/{route_id} | 路由管理 | 软删除路由规则 |
| 创建认证策略 | POST | /api/v1/gateway/auth-policies | 认证授权 | 创建认证策略 |
| 验证 AK/SK 签名 | POST | /api/v1/gateway/auth/verify-signature | 认证授权 | 调试签名验证 |
| 创建限流策略 | POST | /api/v1/gateway/rate-limits | 流量治理 | 创建限流策略 |
| 查询限流状态 | GET | /api/v1/gateway/rate-limits/{policy_id}/status | 流量治理 | 实时限流状态 |
| 创建熔断策略 | POST | /api/v1/gateway/circuit-breakers | 流量治理 | 创建熔断策略 |
| 创建灰度策略 | POST | /api/v1/gateway/canaries | 灰度发布 | 创建灰度发布 |
| 推进灰度阶段 | POST | /api/v1/gateway/canaries/{canary_id}/promote | 灰度发布 | 手动推进灰度 |
| 回滚灰度发布 | POST | /api/v1/gateway/canaries/{canary_id}/rollback | 灰度发布 | 回滚到基线版本 |
| 查询追踪详情 | GET | /api/v1/gateway/traces/{trace_id} | 可观测性 | 分布式追踪详情 |
| 查询实时指标 | GET | /api/v1/gateway/metrics | 可观测性 | Prometheus 指标查询 |
| 查询访问日志 | GET | /api/v1/gateway/access-logs | 可观测性 | 结构化访问日志查询 |

# POS: 408 | TYPE: paragraph
附录 D  版本历史

# POS: 409 | TYPE: paragraph
本文档的版本变更记录如下：

# POS: 410 | TYPE: table
| 版本 | 日期 | 修订内容 | 修订人 |
| --- | --- | --- | --- |
| V0.1.0 | 2026-06-20 | 初始草案，完成总体架构和路由管理模块 | 陈思远 |
| V0.2.0 | 2026-07-01 | 补充认证授权、流量治理、灰度发布模块 | 陈思远 |
| V0.3.0 | 2026-07-10 | 补充可观测性模块、非功能需求、数据模型 | 陈思远 |
| V1.0.0 | 2026-07-16 | 正式版本，补充附录、错误码、接口汇总，评审通过 | 陈思远 |

# POS: 411 | TYPE: paragraph
—  文档结束  —

# POS: 412 | TYPE: embed | file: Microsoft_Excel_Worksheet.xlsx

