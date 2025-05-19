# 执行流程

```markdonw
    WebApp#query
          ↓
  WebApp#getResult
          ↓
JdbcStatement#execute
          ↓
    Command#execute
          ↓
CommandContainer#executeUpdate
          ↓
  CreateTable#update
          ↓
   Schema#createTable
          ↓
   Store#createTable
          ↓              
```