# Chunk 3 of 6
# Source: Microsoft_Word_Document/Microsoft_Word_Document.md
# Pos range: 53 - 358
# Section: 3.  功能需求

# POS: 53 | TYPE: paragraph
3.  功能需求

# POS: 54 | TYPE: paragraph
本章详细描述网关平台的六大核心功能域：路由管理、认证授权、流量治理、灰度发布、可观测性和平台管理。每个 功能域先以 叙述性文字阐述业务场景和设计思路，随后给出具体的  API  接口定义，包括请求方法、路径、参数说明、请求与响应示例。所有  API  均遵循  RESTful  设计规范，返回统一的  JSON  响应格式。

# POS: 55 | TYPE: paragraph
平台所有管理接口的通用响应格式如下。 成功响应 时， HTTP  状态码为  200 ，响应体包含  code （业务状态码，固定为  0 ）、 message （提示信息）和  data （业务数据）三个字段。失败响应时， HTTP  状态码为  400/401/403/404/500  等，响应体包含  code （ 非零错误码 ）、 message （错误描述）和  detail （可选的详细错误信息）三个字段。所有接口均要求请求头携带  X-Request-ID  用于全链路追踪，以及  Authorization  用于身份认证。

# POS: 56 | TYPE: paragraph
通用成功响应： 
 { 
  "code": 0, 
  "message": "success", 
  "data":  { ...  } 
}

# POS: 57 | TYPE: paragraph
通用失败响应： 
 { 
  "code": 1001, 
  "message": "invalid parameter", 
  "detail": "field ' route_name ' is required" 
}

# POS: 58 | TYPE: paragraph
3.1  路由管理

# POS: 59 | TYPE: paragraph
路由管理是网关平台 最 基础的功能域，负责将外部请求按照预设规则转发到正确的后端服务。路由规则支持基于路径前缀、 HTTP  方法、请求头、查询参数、 Cookie  等多维度匹配，并支持优先级排序和冲突检测。当多个规则同时匹配时，优先级数值较小的规则优先执行。路由配置变更后，控制面通过   xDS   协议在 百毫秒 级将新配置推送至所有  Envoy  数据面节点，无需重启或重新加载网关进程。

# POS: 60 | TYPE: paragraph
每条路由规则包含以下核心属性：规则标识（全局唯一  UUID ）、规则名称（便于人工识别）、匹配条件（路径、方法、头、参数的组合）、目标服务（ Kubernetes Service  名称或外部域名）、目标端口、超时时间（默认三十秒，最大支持三百秒）、重试策略（次数、超时、重试条件）、以及是否启用（软删除机制，禁用后规则保留但不再生效）。平台提供路由规则的版本历史功能，每次变更自动生成版本快照，支持一键回滚到任意历史版本。

# POS: 61 | TYPE: paragraph
路由管理控制台提供可视化拓扑图，展示从域名到路由规则再到后端服务的完整调用链路，帮助用户直观理解流量走向。同时提供路由冲突检测功能，当新规则与现有规则存在重叠匹配条件时，系统会自动标记冲突并提示用户调整优先级或匹配条件。

# POS: 62 | TYPE: paragraph
3.1.1  创建路由规则

# POS: 63 | TYPE: paragraph
POST   / api /v1/gateway/routes

# POS: 64 | TYPE: paragraph
摘要：创建一条新的路由规则

# POS: 65 | TYPE: paragraph
该接口用于在指定网关集群中创建路由规则。规则创建后立即生效，控制面会将配置转换为  Envoy RDS  配置并推送至数据面。若存在冲突规则，系统会返回警告信息但不阻止创建。

# POS: 66 | TYPE: paragraph
请求参数 ：

# POS: 67 | TYPE: paragraph
• route_name (string, required):  路由规则名称，全局唯一，长度  2-64  字符

# POS: 68 | TYPE: paragraph
• domain_id (string, required):  所属域名  ID

# POS: 69 | TYPE: paragraph
• match (object, required):  匹配条件，包含  path_prefix 、 methods 、 headers 、 query_params

# POS: 70 | TYPE: paragraph
•   - path_prefix (string, required):  路径前缀，如  /api/v1/orders

# POS: 71 | TYPE: paragraph
•   - methods (array[string]): HTTP  方法列表，如  ["GET","POST"] ，为空则匹配所有方法

# POS: 72 | TYPE: paragraph
•   - headers (array[object]):  请求头匹配规则，支持  exact 、 prefix 、 regex  三种模式

# POS: 73 | TYPE: paragraph
•   - query_params (array[object]):  查询参数匹配规则，模式同  headers

# POS: 74 | TYPE: paragraph
• destination (object, required):  目标服务配置

# POS: 75 | TYPE: paragraph
•   - service_name (string, required): Kubernetes Service  名称

# POS: 76 | TYPE: paragraph
•   - port (integer, required):  目标端口，范围  1-65535

# POS: 77 | TYPE: paragraph
•   - weight (integer):  权重，默认  100 ，用于多版本分流

# POS: 78 | TYPE: paragraph
• timeout (integer):  请求超时时间（秒），默认  30 ，范围  1-300

# POS: 79 | TYPE: paragraph
• retries (object):  重试策略

# POS: 80 | TYPE: paragraph
•   - num_retries (integer):  重试次数，默认  0 ，最大  5

# POS: 81 | TYPE: paragraph
•   - per_try_timeout (integer):  单次重试超时（秒），默认  10

# POS: 82 | TYPE: paragraph
•   - retry_on (array[string]):  触发重试的条件，如  ["5xx","gateway-error","connect-failure"]

# POS: 83 | TYPE: paragraph
• priority (integer):  规则优先级，数值越小优先级越高，默认  100

# POS: 84 | TYPE: paragraph
• enabled (boolean):  是否启用，默认  true

# POS: 85 | TYPE: paragraph
请求示例：

# POS: 86 | TYPE: paragraph
POST /api/v1/gateway/routes HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-001 
 
{ 
   "route_name": "order-service-route", 
  "domain_id": "dom-8a3f2b1c", 
  "match": { 
    "path_prefix": "/api/v2/orders", 
    "methods": ["GET", "POST"], 
    "headers": [ 
      { "name": "X-Api-Version", "mode": "exact", "value": "v2" } 
    ] 
  }, 
  "destination": { 
    "service_name": "order-service", 
    "port": 8080, 
    "weight": 100 
  }, 
  "timeout": 30, 
  "retries": { 
    "num_retries": 2, 
    "per_try_timeout": 10, 
    "retry_on": ["5xx", "connect-failure"] 
  }, 
  "priority": 10, 
  "enabled": true 
}

# POS: 87 | TYPE: paragraph
响应示例：

# POS: 88 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "route created successfully", 
  "data": { 
    "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
    "route_name": "order-service-route", 
    "status": "active", 
    "created_at": "2026-07-16T14:32:18Z", 
    "config_version": "v1", 
    "warnings": [] 
  } 
}

# POS: 89 | TYPE: paragraph
3.1.2  查询路由规则列表

# POS: 90 | TYPE: paragraph
GET   / api /v1/gateway/routes

# POS: 91 | TYPE: paragraph
摘要：分页查询路由规则列表

# POS: 92 | TYPE: paragraph
支持按域名、服务名称、启用状态、创建时间范围进行筛选。返回结果包含每条规则的当前状态（ active/inactive ）、配置版本号、以及最近一次的变更时间和操作人。

# POS: 93 | TYPE: paragraph
请求参数：

# POS: 94 | TYPE: paragraph
•  domain_id  (string, query):  按域名筛选

# POS: 95 | TYPE: paragraph
• service_name (string, query):  按目标服务名称筛选

# POS: 96 | TYPE: paragraph
• enabled (boolean, query):  按启用状态筛选

# POS: 97 | TYPE: paragraph
• page (integer, query):  页码，默认  1

# POS: 98 | TYPE: paragraph
• page_size (integer, query):  每页数量，默认  20 ，最大  100

# POS: 99 | TYPE: paragraph
• sort_by (string, query):  排序字段，可选  created_at/priority/route_name

# POS: 100 | TYPE: paragraph
• sort_order (string, query):  排序方向， asc  或  desc ，默认  desc

# POS: 101 | TYPE: paragraph
请求示例：

# POS: 102 | TYPE: paragraph
GET /api/v1/gateway/routes?domain_id=dom-8a3f2b1c&page=1&page_size=20 HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-002

# POS: 103 | TYPE: paragraph
响应示例：

# POS: 104 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "success", 
  "data": { 
    "total": 156, 
    "page": 1, 
    "page_size": 20, 
    "items": [ 
      { 
        "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
        "route_name": "order-service-route", 
        "domain_id": "dom-8a3f2b1c", 
        "match": { "path_prefix": "/api/v2/orders", "methods": ["GET","POST"] }, 
        "destination": { "service_name": "order-service", "port": 8080, "weight": 100 }, 
         "enabled": true, 
        "priority": 10, 
        "status": "active", 
        "config_version": "v3", 
        "created_at": "2026-07-16T14:32:18Z", 
        "updated_at": "2026-07-16T18:45:22Z", 
        "updated_by": "chen.siyuan@company.com" 
      } 
    ] 
  } 
}

# POS: 105 | TYPE: paragraph
3.1.3  更新路由规则

# POS: 106 | TYPE: paragraph
PUT   /api/v1/gateway/routes/{route_id}

# POS: 107 | TYPE: paragraph
摘要：更新指定路由规则

# POS: 108 | TYPE: paragraph
支持部分更新，未提供的字段保持原值。更新后自动生成新版本快照，原版本保留在历史记录中。若更新导致路由冲突，系统返回警告。

# POS: 109 | TYPE: paragraph
请求参数：

# POS: 110 | TYPE: paragraph
• route_id (string, path, required):  路由规则  ID

# POS: 111 | TYPE: paragraph
• route_name (string, body):  新名称，可选

# POS: 112 | TYPE: paragraph
• match (object, body):  新匹配条件，可选

# POS: 113 | TYPE: paragraph
• destination (object, body):  新目标配置，可选

# POS: 114 | TYPE: paragraph
• timeout (integer, body):  新超时时间，可选

# POS: 115 | TYPE: paragraph
• retries (object, body):  新重试策略，可选

# POS: 116 | TYPE: paragraph
• priority (integer, body):  新优先级，可选

# POS: 117 | TYPE: paragraph
• enabled (boolean, body):  新启用状态，可选

# POS: 118 | TYPE: paragraph
请求示例：

# POS: 119 | TYPE: paragraph
PUT /api/v1/gateway/routes/rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-003 
 
{ 
  "timeout": 45, 
  "retries": { 
    "num_retries": 3, 
    "per_try_timeout": 15, 
     "retry_on": ["5xx", "gateway-error", "connect-failure"] 
  } 
}

# POS: 120 | TYPE: paragraph
响应示例：

# POS: 121 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "route updated successfully", 
  "data": { 
    "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
    "config_version": "v4", 
    "previous_version": "v3", 
    "updated_at": "2026-07-16T19:12:33Z", 
    "warnings": [] 
  } 
}

# POS: 122 | TYPE: paragraph
3.1.4  删除路由规则

# POS: 123 | TYPE: paragraph
DELETE   /api/v1/gateway/routes/{route_id}

# POS: 124 | TYPE: paragraph
摘要：删除指定路由规则

# POS: 125 | TYPE: paragraph
执行软删除，规则标记为  deleted  状态但保留在历史记录中，支持从回收站恢复。强制删除需额外提供  force=true  参数。

# POS: 126 | TYPE: paragraph
请求参数：

# POS: 127 | TYPE: paragraph
• route_id (string, path, required):  路由规则  ID

# POS: 128 | TYPE: paragraph
• force (boolean, query):  是否强制永久删除，默认  false

# POS: 129 | TYPE: paragraph
请求示例：

# POS: 130 | TYPE: paragraph
DELETE /api/v1/gateway/routes/rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a?force=false HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-004

# POS: 131 | TYPE: paragraph
响应示例：

# POS: 132 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "route deleted successfully", 
  "data": { 
    "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
     "deleted_at": "2026-07-16T19:15:44Z", 
    "recoverable_before": "2026-08-16T19:15:44Z" 
  } 
}

# POS: 133 | TYPE: paragraph
3.2  认证授权

# POS: 134 | TYPE: paragraph
认证授权模块是网关平台的安全基石，负责对所有进入微服务集群的请求进行身份验证和权限校验。平台采用「网关统一认证、服务零信任」的安全模型：所有外部请求必须在网关 层完成 认证，认证通过后将解析出的用户身份信息（ User ID 、角色、权限范围）通过请求头透传给后端服务，后端服务无需自行实现认证逻辑，只需 基于透传 的身份信息进行业务权限判断。服务间的东西向通信则通过   mTLS   双向证书认证，确保集群内部流量同样可信。

# POS: 135 | TYPE: paragraph
平台支持多种认证机制，可按路由维度灵活配置。对于面向终端用户的  B  端和  C  端接口，支持  OAuth 2.0 Authorization Code  流程和  JWT Bearer Token  验证；对于第三方系统对接的  Open API ，支持  AK/SK （ Access Key / Secret Key ）签名认证，采用  HMAC-SHA256  算法对请求进行签名，防止请求被篡改和重放攻击；对于内部服务间的调用，通过  SPIFFE/SPIRE  实现自动化的工作负载身份认证，每个  Pod  启动时自动获取  X.509  证书，无需人工配置。

# POS: 136 | TYPE: paragraph
授权方面，平台支持基于   RBAC （ Role-Based  Access  Control ）的粗粒度权限控制和基于  ABAC （ Attribute-Based Access Control ）的细粒度策略控制。 RBAC  通过预定义的角色（如  platform-admin 、 service-owner 、 security-engineer ）映射到具体的  API  操作权限； ABAC  则支持更灵活的策略表达式，例如「仅允许来自  10.0.0.0/8  网段的请求访问  /api/internal/*  路径」或「仅允许具有  finance-read  权限的用户在营业时间内访问财务接口 」。 所有授权策略在  Envoy  层通过  WASM  插件执行，延迟控制在亚毫秒级。

# POS: 137 | TYPE: paragraph
3.2.1  创建认证策略

# POS: 138 | TYPE: paragraph
POST   / api /v1/gateway/auth-policies

# POS: 139 | TYPE: paragraph
摘要：为指定路由创建认证策略

# POS: 140 | TYPE: paragraph
认证策略绑定到路由规则上，当请求匹配该路由时，网关会按照策略配置执行认证。支持多种认证方式组合（如同时要求  JWT  和  IP  白名单），认证失败时返回  401  或  403 。

# POS: 141 | TYPE: paragraph
请求参数 ：

# POS: 142 | TYPE: paragraph
• policy_name (string, required):  策略名称，全局唯一

# POS: 143 | TYPE: paragraph
• route_id (string, required):  绑定的路由规则  ID

# POS: 144 | TYPE: paragraph
• auth_type (string, required):  认证类型，可选  jwt / oauth2 / ak_sk / mtls / none

# POS: 145 | TYPE: paragraph
• jwt_config (object, conditional): JWT  认证配置，当  auth_type=jwt  时必填

# POS: 146 | TYPE: paragraph
•   - issuer (string, required): JWT  签发者，如  https://auth.company.com

# POS: 147 | TYPE: paragraph
•   - jwks_uri (string, required): JWKS  公钥获取地址

# POS: 148 | TYPE: paragraph
•   - audiences (array[string]):  允许的受众列表

# POS: 149 | TYPE: paragraph
•   - claims_to_headers (array[object]):  将  JWT Claim  映射为请求头

# POS: 150 | TYPE: paragraph
• oauth2_config (object, conditional): OAuth 2.0  配置，当  auth_type=oauth2  时必填

# POS: 151 | TYPE: paragraph
•   - token_endpoint (string, required): Token  introspection  端点

# POS: 152 | TYPE: paragraph
•   - client_id (string, required):  客户端  ID

# POS: 153 | TYPE: paragraph
•   - client_secret (string, required):  客户端密钥

# POS: 154 | TYPE: paragraph
• ak_sk_config (object, conditional): AK/SK  配置，当  auth_type=ak_sk  时必填

# POS: 155 | TYPE: paragraph
•   - signature_method (string, required):  签名算法，固定  HMAC-SHA256

# POS: 156 | TYPE: paragraph
•   - header_to_validate (string):  携带签名的请求头名称，默认  Authorization

# POS: 157 | TYPE: paragraph
• ip_whitelist (array[string]):  允许的  IP  地址或  CIDR  段，可选

# POS: 158 | TYPE: paragraph
• rate_limit_per_identity (integer):  单身份每秒请求上限，可选

# POS: 159 | TYPE: paragraph
请求示例：

# POS: 160 | TYPE: paragraph
POST /api/v1/gateway/auth-policies HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-005 
 
{ 
  "policy_name": "order-api-jwt-policy", 
  "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
  "auth_type": "jwt", 
  "jwt_config": { 
    "issuer": "https://auth.company.com", 
     "jwks_uri": "https://auth.company.com/.well-known/jwks.json", 
    "audiences": ["gateway-platform", "order-service"], 
    "claims_to_headers": [ 
      { "claim": "sub", "header": "X-User-ID" }, 
      { "claim": "roles", "header": "X-User-Roles" }, 
      { "claim": "tenant_id", "header": "X-Tenant-ID" } 
    ] 
  }, 
  "ip_whitelist": ["10.0.0.0/8", "172.16.0.0/12"], 
  "rate_limit_per_identity": 1000 
}

# POS: 161 | TYPE: paragraph
响应示例：

# POS: 162 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
   "message": "auth policy created successfully", 
  "data": { 
    "policy_id": "ap-3f2b1c8d-4e5f-6a7b-8c9d-0e1f2a3b4c5d", 
    "policy_name": "order-api-jwt-policy", 
    "status": "active", 
    "created_at": "2026-07-16T20:01:12Z" 
  } 
}

# POS: 163 | TYPE: paragraph
3.2.2  验证  AK/SK  签名

# POS: 164 | TYPE: paragraph
POST   /api/v1/gateway/auth/verify-signature

# POS: 165 | TYPE: paragraph
摘要：验证第三方请求的  AK/SK  签名是否正确

# POS: 166 | TYPE: paragraph
该接口供调试和测试使用，实际认证在网关层自动完成。签名算法遵循标准  HMAC-SHA256  流程，请求头需包含  X-Access-Key 、 X-Signature  和  X-Timestamp 。

# POS: 167 | TYPE: paragraph
请求参数：

# POS: 168 | TYPE: paragraph
• access_key (string, required):  访问密钥标识

# POS: 169 | TYPE: paragraph
• signature (string, required): HMAC-SHA256  签名值， Base64  编码

# POS: 170 | TYPE: paragraph
• timestamp (string, required):  请求时间戳， ISO 8601  格式，有效期  5  分钟

# POS: 171 | TYPE: paragraph
• method (string, required): HTTP  方法，如  GET/POST

# POS: 172 | TYPE: paragraph
• path (string, required):  请求路径，含查询参数

# POS: 173 | TYPE: paragraph
• body (string, optional):  请求体原文，用于计算签名

# POS: 174 | TYPE: paragraph
请求示例：

# POS: 175 | TYPE: paragraph
POST /api/v1/gateway/auth/verify-signature HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-006 
 
{ 
  "access_key": "AK20260716A3F2B1C", 
   "signature": "f7a3b2c1d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1", 
  "timestamp": "2026-07-16T20:05:30Z", 
  "method": "POST", 
  "path": "/api/v1/orders?page=1", 
  "body": "{"user_id":"u123","amount":199.99}" 
}

# POS: 176 | TYPE: paragraph
响应示例：

# POS: 177 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "signature valid", 
  "data": { 
    "valid": true, 
    "access_key": "AK20260716A3F2B1C", 
    "associated_app": "partner-erp-system", 
    "permissions": ["order:read", "order:write"], 
     "expires_at": "2026-12-31T23:59:59Z" 
  } 
}

# POS: 178 | TYPE: paragraph
3.3  流量治理

# POS: 179 | TYPE: paragraph
流量治理模块是网关平台保障服务稳定性的核心能力域，涵盖限流、熔断、负载均衡、超时 重试四 大子系统。这些治理策略并非简单地在网关 层叠加 防护，而是与后端服务的运行时状态深度联动，形成自适应的「感知 - 决策 - 执行」闭环。例如，当 某个服务 的错误率在一分钟内从百分之零点一上升至百分之五时，熔断器会自动打开，切断该服务的流量，同时触发告警通知服务  owner ；当服务恢复后，熔断器进入半开状态，允许少量探测请求通过，验证恢复情况后再完全关闭。

# POS: 180 | TYPE: paragraph
限流策略支持多种维度组合。全局限流控制整个网关集群的总吞吐量，防止集群过载； 路由级限流控制 单条路由的  QPS ，防止单个接口耗尽资源； 用户级限流 基于身份标识（ User ID  或  Access Key ）控制单个用户的调用频率，防止恶意刷接口； IP  级限流 则控制单个  IP  地址的请求速率，防御  DDoS  和爬虫。限流算法采用令牌桶实现，支持突发流量平滑处理，同时提供「预热」模式，在服务启动初期逐步放开流量，避免冷启动冲击。

# POS: 181 | TYPE: paragraph
负载均衡策略不再局限于传统的轮询或随机，而是支持基于实时健康状态的智能调度。 Least Request  算法将请求分配给当前处理中请求数最少的实例； Ring Hash  算法基于请求头（如  User ID ）进行一致性哈希，确保同一用户的请求始终路由到同一实例，适用于需要会话亲和性的场景； Locality Weighted  算法优先将流量分配给同一 可用区 的实例，减少跨区网络延迟，当本 可用区 实例不足时再溢出到其他可用区。

# POS: 182 | TYPE: paragraph
3.3.1  创建限流策略

# POS: 183 | TYPE: paragraph
POST   /api/v1/gateway/rate-limits

# POS: 184 | TYPE: paragraph
摘要：创建限流策略

# POS: 185 | TYPE: paragraph
限流策略可绑定到全局、路由、用户或  IP  维度。当请求触发限流时，网关返回  429 Too Many Requests ，响应头包含  X-RateLimit-Limit 、 X-RateLimit-Remaining  和  X-RateLimit-Reset  三个字段，便于客户端实现退避重试。

# POS: 186 | TYPE: paragraph
请求参数：

# POS: 187 | TYPE: paragraph
• policy_name (string, required):  策略名称

# POS: 188 | TYPE: paragraph
• scope (string, required):  作用域，可选  global / route / user / ip

# POS: 189 | TYPE: paragraph
• route_id (string, conditional):  路由  ID ，当  scope=route  时必填

# POS: 190 | TYPE: paragraph
• algorithm (string, required):  限流算法，可选  token_bucket / sliding_window / fixed_window

# POS: 191 | TYPE: paragraph
• limit (object, required):  限流阈值

# POS: 192 | TYPE: paragraph
•   - requests_per_second (integer):  每秒请求上限

# POS: 193 | TYPE: paragraph
•   - requests_per_minute (integer):  每分钟请求上限

# POS: 194 | TYPE: paragraph
•   - requests_per_hour (integer):  每小时请求上限

# POS: 195 | TYPE: paragraph
•   - burst (integer):  令牌桶突发容量，默认等于  requests_per_second

# POS: 196 | TYPE: paragraph
• warmup (object, optional):  预热配置

# POS: 197 | TYPE: paragraph
•   - enabled (boolean):  是否启用预热

# POS: 198 | TYPE: paragraph
•   - duration_seconds (integer):  预热持续时间

# POS: 199 | TYPE: paragraph
•   - initial_rate_percent (integer):  初始流量百分比，默认  10

# POS: 200 | TYPE: paragraph
• action (string, required):  触发限流后的动作，可选  reject / throttle / redirect

# POS: 201 | TYPE: paragraph
•   - reject:  直接拒绝，返回  429

# POS: 202 | TYPE: paragraph
•   - throttle:  延迟处理，排队等待

# POS: 203 | TYPE: paragraph
•   - redirect:  重定向到降级页面或备用服务

# POS: 204 | TYPE: paragraph
• enabled (boolean):  是否启用，默认  true

# POS: 205 | TYPE: paragraph
请求示例：

# POS: 206 | TYPE: paragraph
POST /api/v1/gateway/rate-limits HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-007 
 
{ 
  "policy_name": "order-api-user-limit", 
  "scope": "user", 
   "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
  "algorithm": "token_bucket", 
  "limit": { 
    "requests_per_second": 50, 
    "requests_per_minute": 1000, 
    "burst": 100 
  }, 
  "warmup": { 
    "enabled": true, 
    "duration_seconds": 300, 
    "initial_rate_percent": 10 
  }, 
  "action": "reject", 
  "enabled": true 
}

# POS: 207 | TYPE: paragraph
响应示例：

# POS: 208 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "rate limit policy created", 
  "data": { 
    "policy_id": "rl-9e5f2b1c-8d4e-9f3a-7d4e-9f2a8c1b4a3d", 
     "policy_name": "order-api-user-limit", 
    "status": "active", 
    "created_at": "2026-07-16T20:18:45Z" 
  } 
}

# POS: 209 | TYPE: paragraph
3.3.2  查询实时限流状态

# POS: 210 | TYPE: paragraph
GET   /api/v1/gateway/rate-limits/{policy_id}/status

# POS: 211 | TYPE: paragraph
摘要：查询指定限流策略的实时状态

# POS: 212 | TYPE: paragraph
返回当前令牌桶中的剩余令牌数、最近一分钟的请求通过率和拒绝率，以及策略生效的  Envoy  节点列表。

# POS: 213 | TYPE: paragraph
请求参数：

# POS: 214 | TYPE: paragraph
• policy_id (string, path, required):  限流策略  ID

# POS: 215 | TYPE: paragraph
请求示例：

# POS: 216 | TYPE: paragraph
GET /api/v1/gateway/rate-limits/rl-9e5f2b1c-8d4e-9f3a-7d4e-9f2a8c1b4a3d/status HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-008

# POS: 217 | TYPE: paragraph
响应示例：

# POS: 218 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "success", 
  "data": { 
    "policy_id": "rl-9e5f2b1c-8d4e-9f3a-7d4e-9f2a8c1b4a3d", 
    "policy_name": "order-api-user-limit", 
    "tokens_remaining": 73, 
    "tokens_capacity": 100, 
    "pass_rate_1m": 0.98, 
    "reject_rate_1m": 0.02, 
    "total_requests_1m": 4521, 
    "active_nodes": [ 
      { "node_id": "envoy-01", "zone": "zone-a", "tokens_remaining": 25 }, 
       { "node_id": "envoy-02", "zone": "zone-b", "tokens_remaining": 24 }, 
      { "node_id": "envoy-03", "zone": "zone-c", "tokens_remaining": 24 } 
    ], 
    "last_updated": "2026-07-16T20:20:15Z" 
  } 
}

# POS: 219 | TYPE: paragraph
3.3.3  创建熔断策略

# POS: 220 | TYPE: paragraph
POST   /api/v1/gateway/circuit-breakers

# POS: 221 | TYPE: paragraph
摘要：创建熔断策略

# POS: 222 | TYPE: paragraph
熔断策略作用于后端服务集群，当错误率或慢请求比例超过阈值时自动切断流量。熔断器状态包括  Closed （正常）、 Open （熔断）、 Half-Open （探测）三种。

# POS: 223 | TYPE: paragraph
请求参数：

# POS: 224 | TYPE: paragraph
• policy_name (string, required):  策略名称

# POS: 225 | TYPE: paragraph
• service_name (string, required):  目标服务名称

# POS: 226 | TYPE: paragraph
• error_threshold (object, required):  错误率阈值配置

# POS: 227 | TYPE: paragraph
•   - consecutive_errors (integer):  连续错误次数触发熔断，默认  5

# POS: 228 | TYPE: paragraph
•   - error_percent (integer):  错误百分比触发熔断，默认  50

# POS: 229 | TYPE: paragraph
•   - min_request_count (integer):  最小请求数，低于此值不触发，默认  10

# POS: 230 | TYPE: paragraph
• slow_request_threshold (object, optional):  慢请求阈值

# POS: 231 | TYPE: paragraph
•   - threshold_ms (integer):  慢请求判定阈值（毫秒），默认  500

# POS: 232 | TYPE: paragraph
•   - slow_percent (integer):  慢请求百分比触发熔断，默认  80

# POS: 233 | TYPE: paragraph
• timeout (object, required):  熔断超时配置

# POS: 234 | TYPE: paragraph
•   - base_ejection_time_ms (integer):  基础驱逐时间（毫秒），默认  30000

# POS: 235 | TYPE: paragraph
•   - max_ejection_percent (integer):  最大驱逐实例百分比，默认  50

# POS: 236 | TYPE: paragraph
• half_open_config (object, optional):  半开状态配置

# POS: 237 | TYPE: paragraph
•   - max_requests (integer):  半开状态允许的最大探测请求数，默认  3

# POS: 238 | TYPE: paragraph
•   - successful_requests_needed (integer):  成功请求数关闭熔断，默认  2

# POS: 239 | TYPE: paragraph
• enabled (boolean):  是否启用，默认  true

# POS: 240 | TYPE: paragraph
请求示例：

# POS: 241 | TYPE: paragraph
POST /api/v1/gateway/circuit-breakers HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-009 
 
{ 
  "policy_name": "payment-service-cb", 
  "service_name": "payment-service", 
  "error_threshold": { 
    "consecutive_errors": 5, 
    "error_percent": 50, 
     "min_request_count": 20 
  }, 
  "slow_request_threshold": { 
    "threshold_ms": 800, 
    "slow_percent": 70 
  }, 
  "timeout": { 
    "base_ejection_time_ms": 30000, 
    "max_ejection_percent": 30 
  }, 
  "half_open_config": { 
    "max_requests": 5, 
    "successful_requests_needed": 3 
  }, 
  "enabled": true 
}

# POS: 242 | TYPE: paragraph
响应示例：

# POS: 243 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "circuit breaker policy created", 
  "data": { 
    "policy_id": "cb-1c8d4e9f-3a7d-4e9f-2b1c-8d4e9f3a7d4e", 
     "policy_name": "payment-service-cb", 
    "status": "active", 
    "created_at": "2026-07-16T20:25:33Z" 
  } 
}

# POS: 244 | TYPE: paragraph
3.4  灰度发布

# POS: 245 | TYPE: paragraph
灰度发布模块为 微服务 的版本演进提供安全、可控的发布能力。传统的全量发布方式存在风险集中、 回滚困难 、影响范围不可控等问题，而灰度发布通过将新版本逐步暴露给特定用户群体或流量比例，在小范围内验证新版本的稳定性后再扩大覆盖范围，实现「渐进式交付」。平台支持多种灰度策略，可按流量比例、用户属性、地域、设备、请求头等多种维度进行分流，满足不同业务场景下的发布需求。

# POS: 246 | TYPE: paragraph
流量比例灰度是 最 基础的策略，将总流量按百分比分配给新旧版本。例如，初始阶段将百分之五的流量导向新版本，观察错误率和延迟指标； 若指标 正常，逐步提升至百分之二十、百分之五十，最终全量切换。用户属性灰度则基于用户身份特征进行分流，例如仅对内部员工或  VIP  用户开放新版本，或者基于用户  ID  的哈希值进行一致性分流，确保同一用户始终访问同一版本。地域灰度适用于需要按区域逐步推进的场景，例如先在华东区域发布验证，再扩展至华北、华南。请求头灰度则通过解析特定的请求头（如  X-Canary-Version ）将携带特定标记的请求路由到指定版本，便于测试团队进行定向验证。

# POS: 247 | TYPE: paragraph
灰度发布与流量治理模块深度联动。当新版本的健康检查失败率超过阈值时，灰度策略会自动暂停流量注入，防止故障扩散；当新版本的  P99  延迟持续高于基线版本百分之二十时，系统会触发告警并建议回滚。所有灰度操作均记录审计日志，包括每次流量调整的时间、操作人、调整前后的比例及当时的监控指标快照。

# POS: 248 | TYPE: paragraph
3.4.1  创建灰度发布策略

# POS: 249 | TYPE: paragraph
POST   / api /v1/gateway/canaries

# POS: 250 | TYPE: paragraph
摘要：创建灰度发布策略

# POS: 251 | TYPE: paragraph
灰度策略作用于已有路由规则，通过修改目标服务的权重分配实现流量分流。支持同时配置多个灰度版本，每个版本可独立 设置分流 条件和权重。

# POS: 252 | TYPE: paragraph
请求参数：

# POS: 253 | TYPE: paragraph
•  canary_name  (string, required):  灰度发布名称

# POS: 254 | TYPE: paragraph
• route_id (string, required):  绑定的路由规则  ID

# POS: 255 | TYPE: paragraph
• baseline_version (object, required):  基线版本配置

# POS: 256 | TYPE: paragraph
•   - service_name (string, required):  基线服务名称

# POS: 257 | TYPE: paragraph
•   - port (integer, required):  服务端口

# POS: 258 | TYPE: paragraph
•   - version_tag (string, required):  版本标签，如  v1.2.3

# POS: 259 | TYPE: paragraph
• canary_versions (array[object], required):  灰度版本列表

# POS: 260 | TYPE: paragraph
•   - service_name (string, required):  灰度服务名称

# POS: 261 | TYPE: paragraph
•   - port (integer, required):  服务端口

# POS: 262 | TYPE: paragraph
•   - version_tag (string, required):  版本标签，如  v1.3.0-beta

# POS: 263 | TYPE: paragraph
•   - weight (integer, required):  流量权重百分比， 0-100

# POS: 264 | TYPE: paragraph
•   - conditions (array[object]):  分流条件

# POS: 265 | TYPE: paragraph
•     - type (string):  条件类型，可选  header / cookie / query / user_attr / region / percentage

# POS: 266 | TYPE: paragraph
•     - key (string):  条件键名

# POS: 267 | TYPE: paragraph
•     - operator (string):  操作符，可选  equal / prefix / regex / in / range

# POS: 268 | TYPE: paragraph
•     - value (string):  条件值

# POS: 269 | TYPE: paragraph
• auto_promotion (object, optional):  自动推进配置

# POS: 270 | TYPE: paragraph
•   - enabled (boolean):  是否启用自动推进

# POS: 271 | TYPE: paragraph
•   - stages (array[object]):  推进阶段

# POS: 272 | TYPE: paragraph
•     - weight (integer):  该阶段的目标权重

# POS: 273 | TYPE: paragraph
•     - duration_minutes (integer):  该阶段持续时间

# POS: 274 | TYPE: paragraph
•     - error_rate_threshold (number):  错误率阈值，超过则暂停

# POS: 275 | TYPE: paragraph
•     - latency_p99_threshold_ms (integer): P99  延迟阈值

# POS: 276 | TYPE: paragraph
• rollback_on_failure (boolean):  失败时是否自动回滚，默认  true

# POS: 277 | TYPE: paragraph
• enabled (boolean):  是否启用，默认  true

# POS: 278 | TYPE: paragraph
请求示例：

# POS: 279 | TYPE: paragraph
POST /api/v1/gateway/canaries HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-010 
 
{ 
  "canary_name": "order-service-v130-rollout", 
  "route_id": "rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
  "baseline_version": { 
    "service_name": "order-service", 
    "port": 8080, 
    "version_tag": "v1.2.3" 
  }, 
  "canary_versions": [ 
     { 
      "service_name": "order-service", 
      "port": 8080, 
      "version_tag": "v1.3.0", 
      "weight": 5, 
      "conditions": [ 
        { "type": "percentage", "operator": "range", "value": "0-5" }, 
        { "type": "header", "key": "X-Canary-Test", "operator": "equal", "value": "true" } 
      ] 
    } 
  ], 
  "auto_promotion": { 
    "enabled": true, 
    "stages": [ 
      { "weight": 5, "duration_minutes": 60, "error_rate_threshold": 0.01, "latency_p99_threshold_ms": 500 }, 
       { "weight": 20, "duration_minutes": 120, "error_rate_threshold": 0.005, "latency_p99_threshold_ms": 400 }, 
      { "weight": 50, "duration_minutes": 180, "error_rate_threshold": 0.005, "latency_p99_threshold_ms": 400 }, 
      { "weight": 100, "duration_minutes": 0, "error_rate_threshold": 0.005, "latency_p99_threshold_ms": 400 } 
    ] 
  }, 
  "rollback_on_failure": true, 
  "enabled": true 
}

# POS: 280 | TYPE: paragraph
响应示例：

# POS: 281 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "canary strategy created", 
   "data": { 
    "canary_id": "cn-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
    "canary_name": "order-service-v130-rollout", 
    "status": "running", 
    "current_stage": 0, 
    "current_weight": 5, 
    "created_at": "2026-07-16T20:35:12Z", 
    "next_promotion_at": "2026-07-16T21:35:12Z" 
  } 
}

# POS: 282 | TYPE: paragraph
3.4.2  手动推进灰度阶段

# POS: 283 | TYPE: paragraph
POST   / api /v1/gateway/canaries/{ canary_id }/promote

# POS: 284 | TYPE: paragraph
摘要：手动推进灰度发布到下一阶段

# POS: 285 | TYPE: paragraph
当自动推进关闭或需要人工确认时，通过该接口手动触发阶段推进。推进前系统会检查当前阶段的监控指标是否满足阈值要求。

# POS: 286 | TYPE: paragraph
请求参数：

# POS: 287 | TYPE: paragraph
•  canary_id  (string, path, required):  灰度策略  ID

# POS: 288 | TYPE: paragraph
• force (boolean, query):  是否强制推进，忽略指标检查，默认  false

# POS: 289 | TYPE: paragraph
• reason (string, body):  推进原因说明，用于审计记录

# POS: 290 | TYPE: paragraph
请求示例：

# POS: 291 | TYPE: paragraph
POST /api/v1/gateway/canaries/cn-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a/promote?force=false HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-011 
 
{ 
  "reason": " 第一阶段运行正常，错误率  0.002% ， P99  延迟  320ms ，满足推进条件 " 
}

# POS: 292 | TYPE: paragraph
响应示例：

# POS: 293 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "canary promoted to next stage", 
  "data": { 
     "canary_id": "cn-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
    "previous_stage": 0, 
    "current_stage": 1, 
    "previous_weight": 5, 
    "current_weight": 20, 
    "promoted_at": "2026-07-16T21:40:18Z", 
    "promoted_by": "chen.siyuan@company.com", 
    "next_promotion_at": "2026-07-16T23:40:18Z", 
    "metrics_snapshot": { 
      "error_rate": 0.002, 
      "latency_p99_ms": 320, 
      "total_requests": 128450 
    } 
  } 
}

# POS: 294 | TYPE: paragraph
3.4.3  回滚灰度发布

# POS: 295 | TYPE: paragraph
POST   /api/v1/gateway/canaries/{canary_id}/rollback

# POS: 296 | TYPE: paragraph
摘要：将灰度发布回滚到基线版本

# POS: 297 | TYPE: paragraph
回滚操作将立即将所有流量切回基线版本，灰度版本不再接收任何请求。回滚后灰度策略进入  rolled_back  状态，可查看历史记录但不可重新激活。

# POS: 298 | TYPE: paragraph
请求参数：

# POS: 299 | TYPE: paragraph
• canary_id (string, path, required):  灰度策略  ID

# POS: 300 | TYPE: paragraph
• reason (string, body):  回滚原因，必填，用于审计和问题追溯

# POS: 301 | TYPE: paragraph
请求示例：

# POS: 302 | TYPE: paragraph
POST /api/v1/gateway/canaries/cn-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a/rollback HTTP/1.1 
Host: gateway-admin.company.com 
Content-Type: application/json 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-012 
 
{ 
  "reason": " 第二阶段错误率升至  2.1% ，超过阈值  0.5% ，触发自动回滚 " 
}

# POS: 303 | TYPE: paragraph
响应示例：

# POS: 304 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
   "code": 0, 
  "message": "canary rolled back successfully", 
  "data": { 
    "canary_id": "cn-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a", 
    "status": "rolled_back", 
    "rolled_back_at": "2026-07-16T22:15:33Z", 
    "rolled_back_by": "system_auto", 
    "reason": " 第二阶段错误率升至  2.1% ，超过阈值  0.5% ，触发自动回滚 ", 
    "traffic_restored_to": "v1.2.3" 
  } 
}

# POS: 305 | TYPE: paragraph
3.5  可观测性

# POS: 306 | TYPE: paragraph
可观测性模块是网关平台的「神经系统」，负责采集、存储、分析和 展示全 链路运行时数据，使运维团队能够「看见」流量在系统中的完整流转路径，快速定位异常根因。平台遵循   OpenTelemetry   标准，统一输出追踪（ Traces ）、指标（ Metrics ）和日志（ Logs ）三类遥测数据，避免传统方案中多个工具数据割裂、关联困难的问题。

# POS: 307 | TYPE: paragraph
分布式追踪是故障定位的核心手段。每个进入网关的请求都会被分配一个全局唯一的  Trace ID ，该  ID  随着请求在服务间的传递而透传，形成完整的调用链。 Envoy  原生支持生成和传递  Zipkin / Jaeger  格式的  Span ，平台在此基础上扩展了自定义标签，包括路由名称、目标服务、响应状态、延迟分段、是否触发限流或熔断等。追踪数据采样策略支持头部驱动采样（根据  X-B3-Sampled  头决定）和概率采样（默认百分之一，错误请求强制采样），在数据量和覆盖度之间取得平衡。

# POS: 308 | TYPE: paragraph
指标监控覆盖网关运行的各个维度。基础指标包括  QPS 、延迟分位数（ P50/P90/P95/P99/P99.9 ）、错误率、连接数、内存和  CPU  使用率；业务指标包括各路由的流量分布、各服务的调用成功率、各限流策略的触发频率、各熔断器的状态转换次数。所有指标以  Prometheus  格式暴露，通过  Grafana  进行可视化展示。平台预置了二十余个监控  Dashboard ，涵盖网关概览、单路由详情、 单服务 详情、安全事件、灰度发布进度等场景。

# POS: 309 | TYPE: paragraph
日志系统采用结构化日志设计，每条日志均为  JSON  格式，包含时间戳、 Trace ID 、 Span ID 、日志级别、组件名称、日志内容等字段。访问日志按  W3C  扩展格式输出，包含客户端  IP 、请求方法、路径、状态码、响应大小、处理延迟、目标服务、路由规则等二十余个字段。日志通过  Fluent Bit  采集后写入  Elasticsearch ，支持全文检索和复杂过滤，典型查询如「查找过去一小时内  / api /v2/orders  路径且延迟超过一秒的所有请求」可在三秒内返回结果。

# POS: 310 | TYPE: paragraph
3.5.1  查询分布式追踪详情

# POS: 311 | TYPE: paragraph
GET   / api /v1/gateway/traces/{ trace_id }

# POS: 312 | TYPE: paragraph
摘要：根据  Trace ID  查询完整的分布式追踪链路

# POS: 313 | TYPE: paragraph
返回该  Trace  下的所有  Span  信息，包括每个  Span  的开始时间、持续时间、服务名称、操作名称、标签和日志事件 。 支持按时间范围筛选子  Span 。

# POS: 314 | TYPE: paragraph
请求参数 ：

# POS: 315 | TYPE: paragraph
• trace_id (string, path, required): Trace ID ，十六进制字符串

# POS: 316 | TYPE: paragraph
• include_logs (boolean, query):  是否包含  Span  内的日志事件，默认  true

# POS: 317 | TYPE: paragraph
• include_tags (boolean, query):  是否包含标签，默认  true

# POS: 318 | TYPE: paragraph
请求示例：

# POS: 319 | TYPE: paragraph
GET /api/v1/gateway/traces/7d4e9f2a8c1b4a3d9e5f2b1c8d4e9f3a?include_logs=true&include_tags=true HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-013

# POS: 320 | TYPE: paragraph
响应示例：

# POS: 321 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "success", 
  "data": { 
    "trace_id": "7d4e9f2a8c1b4a3d9e5f2b1c8d4e9f3a", 
    "duration_ms": 245, 
    "span_count": 5, 
    "spans": [ 
      { 
        "span_id": "a1b2c3d4e5f6a7b8", 
         "parent_span_id": null, 
        "service_name": "gateway-envoy", 
        "operation_name": "ingress /api/v2/orders", 
        "start_time": "2026-07-16T20:45:12.123Z", 
        "duration_ms": 245, 
        "tags": { 
          "http.method": "POST", 
          "http.status_code": "200", 
          "http.url": "/api/v2/orders", 
          "route.name": "order-service-route", 
          "destination.service": "order-service", 
          "response.size": 1024 
        }, 
        "logs": [ 
           { "timestamp": "2026-07-16T20:45:12.123Z", "event": "request_received" }, 
          { "timestamp": "2026-07-16T20:45:12.368Z", "event": "response_sent" } 
        ] 
      }, 
      { 
        "span_id": "b2c3d4e5f6a7b8c9", 
        "parent_span_id": "a1b2c3d4e5f6a7b8", 
        "service_name": "order-service", 
        "operation_name": "POST /orders", 
        "start_time": "2026-07-16T20:45:12.145Z", 
        "duration_ms": 198, 
        "tags": { 
          "db.query_time_ms": 45, 
           "cache.hit": "true" 
        } 
      } 
    ] 
  } 
}

# POS: 322 | TYPE: paragraph
3.5.2  查询实时指标

# POS: 323 | TYPE: paragraph
GET   / api /v1/gateway/metrics

# POS: 324 | TYPE: paragraph
摘要：查询网关实时指标数据

# POS: 325 | TYPE: paragraph
支持按维度聚合查询，可获取指定时间范围内的  QPS 、延迟、错误率等指标。返回数据格式兼容  Prometheus Query API ，便于与现有监控体系集成。

# POS: 326 | TYPE: paragraph
请求参数：

# POS: 327 | TYPE: paragraph
•  metric_name  (string, query, required):  指标名称，如   gateway_qps  /  gateway_latency  /  gateway_error_rate

# POS: 328 | TYPE: paragraph
• dimensions (string, query):  维度过滤，如  route=order-service- route,service =order-service

# POS: 329 | TYPE: paragraph
• aggregation (string, query):  聚合方式，可选  sum / avg / max / min / percentile

# POS: 330 | TYPE: paragraph
• percentile (number, query):  分位数值，当  aggregation=percentile  时必填，如  0.99

# POS: 331 | TYPE: paragraph
• start_time (string, query):  开始时间， ISO 8601  格式

# POS: 332 | TYPE: paragraph
• end_time (string, query):  结束时间， ISO 8601  格式

# POS: 333 | TYPE: paragraph
• step (string, query):  时间粒度，如  1m / 5m / 1h

# POS: 334 | TYPE: paragraph
请求示例：

# POS: 335 | TYPE: paragraph
GET /api/v1/gateway/metrics?metric_name=gateway_latency&dimensions=route=order-service-route&aggregation=percentile&percentile=0.99&start_time=2026-07-16T20:00:00Z&end_time=2026-07-16T21:00:00Z&step=5m HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
X-Request-ID: req-20260716-014

# POS: 336 | TYPE: paragraph
响应示例：

# POS: 337 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "success", 
  "data": { 
    "metric_name": "gateway_latency", 
    "aggregation": "percentile", 
    "percentile": 0.99, 
    "dimensions": { "route": "order-service-route" }, 
    "values": [ 
      { "timestamp": "2026-07-16T20:00:00Z", "value": 320.5 }, 
      { "timestamp": "2026-07-16T20:05:00Z", "value": 315.2 }, 
      { "timestamp": "2026-07-16T20:10:00Z", "value": 450.8 }, 
       { "timestamp": "2026-07-16T20:15:00Z", "value": 298.1 }, 
      { "timestamp": "2026-07-16T20:20:00Z", "value": 310.4 }, 
      { "timestamp": "2026-07-16T20:25:00Z", "value": 305.6 }, 
      { "timestamp": "2026-07-16T20:30:00Z", "value": 312.3 }, 
      { "timestamp": "2026-07-16T20:35:00Z", "value": 308.9 }, 
      { "timestamp": "2026-07-16T20:40:00Z", "value": 315.7 }, 
      { "timestamp": "2026-07-16T20:45:00Z", "value": 325.1 }, 
      { "timestamp": "2026-07-16T20:50:00Z", "value": 318.4 }, 
       { "timestamp": "2026-07-16T20:55:00Z", "value": 322.6 }, 
      { "timestamp": "2026-07-16T21:00:00Z", "value": 320.0 } 
    ], 
    "unit": "ms" 
  } 
}

# POS: 338 | TYPE: paragraph
3.5.3  查询访问日志

# POS: 339 | TYPE: paragraph
GET   / api /v1/gateway/access-logs

# POS: 340 | TYPE: paragraph
摘要：分页查询网关访问日志

# POS: 341 | TYPE: paragraph
支持多维度过滤和全文检索，返回结构化日志数据。单次查询最大返回一万条记录，超过时需分页获取。

# POS: 342 | TYPE: paragraph
请求参数 ：

# POS: 343 | TYPE: paragraph
• route_id (string, query):  按路由规则筛选

# POS: 344 | TYPE: paragraph
• service_name (string, query):  按目标服务筛选

# POS: 345 | TYPE: paragraph
• status_code (integer, query):  按  HTTP  状态码筛选

# POS: 346 | TYPE: paragraph
• client_ip (string, query):  按客户端  IP  筛选

# POS: 347 | TYPE: paragraph
• method (string, query):  按  HTTP  方法筛选

# POS: 348 | TYPE: paragraph
• path (string, query):  按请求路径筛选，支持前缀匹配

# POS: 349 | TYPE: paragraph
• latency_min_ms (integer, query):  最小延迟（毫秒）

# POS: 350 | TYPE: paragraph
• latency_max_ms (integer, query):  最大延迟（毫秒）

# POS: 351 | TYPE: paragraph
• start_time (string, query):  开始时间

# POS: 352 | TYPE: paragraph
• end_time (string, query):  结束时间

# POS: 353 | TYPE: paragraph
• page (integer, query):  页码，默认  1

# POS: 354 | TYPE: paragraph
• page_size (integer, query):  每页数量，默认  50 ，最大  1000

# POS: 355 | TYPE: paragraph
请求示例：

# POS: 356 | TYPE: paragraph
GET /api/v1/gateway/access-logs?route_id=rt-7d4e9f2a-8c1b-4a3d-9e5f-2b1c8d4e9f3a&status_code=500&start_time=2026-07-16T20:00:00Z&end_time=2026-07-16T21:00:00Z&page=1&page_size=20 HTTP/1.1 
Host: gateway-admin.company.com 
Authorization: Bearer eyJhbGciOiJSUzI1NiIs... 
 X-Request-ID: req-20260716-015

# POS: 357 | TYPE: paragraph
响应示例：

# POS: 358 | TYPE: paragraph
HTTP/1.1 200 OK 
Content-Type: application/json 
 
{ 
  "code": 0, 
  "message": "success", 
  "data": { 
    "total": 3, 
    "page": 1, 
    "page_size": 20, 
    "items": [ 
      { 
        "timestamp": "2026-07-16T20:18:33.452Z", 
        "trace_id": "7d4e9f2a8c1b4a3d9e5f2b1c8d4e9f3a", 
        "client_ip": "10.0.1.23", 
        "method": "POST", 
        "path": "/api/v2/orders", 
        "status_code": 500, 
        "response_size": 256, 
        "latency_ms": 2450, 
         "route_name": "order-service-route", 
        "destination_service": "order-service", 
        "destination_pod": "order-service-7d4e9f2a-8c1b-4a3d", 
        "user_id": "u123456", 
        "request_headers": { 
          "User-Agent": "Mozilla/5.0", 
          "X-Request-ID": "req-20260716-015" 
        } 
      } 
    ] 
  } 
}

