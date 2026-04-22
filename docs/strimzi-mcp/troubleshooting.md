+++
title = 'Troubleshooting'
weight = 5
+++

Resolve common issues when using the Strimzi MCP Server.

## Server issues

### Server does not start

**Symptom**: MCP server fails to start or crashes immediately

**Common Causes**:

1. **Kubernetes connection failure**
   ```
   Error: Unable to connect to Kubernetes API
   ```
   
   **Solution**:
   ```bash
   # Verify kubectl works
   kubectl cluster-info
   
   # Check kubeconfig
   echo $KUBECONFIG
   kubectl config view
   
   # Test with a simple command
   kubectl get pods
   ```

2. **Port already in use**
   ```
   Error: Address already in use: bind
   ```
   
   **Solution**:
   ```bash
   # Find process using port 8080
   lsof -i :8080
   
   # Kill the process or use a different port
   ./mvnw quarkus:dev -Dquarkus.http.port=8081
   ```

3. **Missing Strimzi CRDs**
   ```
   Error: CustomResourceDefinition kafka.strimzi.io not found
   ```
   
   **Solution**:
   ```bash
   # Verify Strimzi CRDs exist
   kubectl get crd | grep strimzi
   
   # Deploy Strimzi operator if missing
   ./dev/scripts/setup-strimzi.sh deploy
   ```

### Server starts but AI assistant cannot connect

**Symptom**: Server is running but AI assistant cannot discover tools

**Diagnosis**:
```bash
# Test MCP endpoint
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'
```

**Expected**: JSON response with server capabilities

**If connection refused**:
- Check server is running: `curl http://localhost:8080/q/health`
- Verify port forwarding (if using Kubernetes)
- Check firewall rules

**If 404 error**:
- Verify endpoint path is `/mcp` not `/`
- Check server logs for startup errors

### Tools return errors

**Symptom**: AI assistant reports tool execution failures

**Common Errors**:

1. **"Kafka cluster not found"**
   ```
   Error: Kafka cluster 'my-cluster' not found
   ```
   
   **Solution**:
   ```bash
   # List Kafka clusters
   kubectl get kafka --all-namespaces
   
   # Check specific namespace
   kubectl get kafka -n strimzi-kafka
   ```

2. **"Permission denied"**
   ```
   Error: User cannot list kafkas in namespace 'kafka-ns'
   ```
   
   **Solution**:
   ```bash
   # Check RBAC permissions
   kubectl auth can-i list kafkas --all-namespaces
   
   # For in-cluster deployment, check ServiceAccount permissions
   kubectl describe clusterrole streamshub-mcp-reader
   ```

3. **"Namespace not found"**
   ```
   Error: Namespace 'kafka-ns' not found
   ```
   
   **Solution**:
   ```bash
   # List available namespaces
   kubectl get namespaces
   
   # Create namespace if needed
   kubectl create namespace kafka-ns
   ```

## Kubernetes deployment issues

### Pods not starting

**Symptom**: MCP server pods stuck in Pending or CrashLoopBackOff

**Diagnosis**:
```bash
# Check pod status
kubectl -n streamshub-mcp get pods

# View pod events
kubectl -n streamshub-mcp describe pod <pod-name>

# Check logs
kubectl -n streamshub-mcp logs <pod-name>
```

**Common Issues**:

1. **Image pull failure**
   ```
   Failed to pull image: unauthorized
   ```
   
   **Solution**:
   ```bash
   # Verify image exists
   docker pull quay.io/streamshub/strimzi-mcp:latest
   
   # Create image pull secret if needed
   kubectl create secret docker-registry regcred \
     --docker-server=quay.io \
     --docker-username=<username> \
     --docker-password=<password> \
     -n streamshub-mcp
   
   # Update deployment to use secret
   kubectl patch deployment streamshub-strimzi-mcp \
     -n streamshub-mcp \
     -p '{"spec":{"template":{"spec":{"imagePullSecrets":[{"name":"regcred"}]}}}}'
   ```

2. **RBAC issues**
   ```
   Error: User "system:serviceaccount:streamshub-mcp:streamshub-mcp" 
   cannot list kafkas
   ```
   
   **Solution**:
   ```bash
   # Verify ClusterRoleBinding exists
   kubectl get clusterrolebinding streamshub-mcp-reader
   
   # Recreate if missing
   kubectl apply -f install/004-ClusterRoleBinding.yaml
   ```

3. **Insufficient resources**
   ```
   0/3 nodes are available: insufficient memory
   ```
   
   **Solution**:
   ```bash
   # Reduce resource requests
   kubectl edit deployment streamshub-strimzi-mcp -n streamshub-mcp
   
   # Or scale down other workloads
   ```

### Service not accessible

**Symptom**: Cannot connect to MCP server service

**Diagnosis**:
```bash
# Check service
kubectl -n streamshub-mcp get svc streamshub-strimzi-mcp

# Check endpoints
kubectl -n streamshub-mcp get endpoints streamshub-strimzi-mcp

# Test from within cluster
kubectl -n streamshub-mcp run test --rm -it --image=curlimages/curl -- \
  curl http://streamshub-strimzi-mcp:8080/q/health
```

**Solutions**:

1. **No endpoints**: Pod not ready
   ```bash
   # Check pod readiness
   kubectl -n streamshub-mcp get pods
   
   # Check readiness probe
   kubectl -n streamshub-mcp describe pod <pod-name>
   ```

2. **Port mismatch**: Service port does not match container port
   ```bash
   # Verify service configuration
   kubectl -n streamshub-mcp get svc streamshub-strimzi-mcp -o yaml
   ```

## AI assistant issues

### Claude Desktop not showing tools

**Symptom**: MCP server connected but tools do not appear

**Solutions**:

1. **Restart Claude Desktop**
   - Completely quit and restart the application
   - Check configuration file was saved correctly

2. **Verify configuration**
   ```bash
   # macOS
   cat ~/Library/Application\ Support/Claude/claude_desktop_config.json
   
   # Windows
   type %APPDATA%\Claude\claude_desktop_config.json
   ```
   
   Should contain:
   ```json
   {
     "mcpServers": {
       "strimzi": {
         "transport": "http",
         "url": "http://localhost:8080/mcp"
       }
     }
   }
   ```

3. **Check server accessibility**
   ```bash
   # Test from your machine
   curl http://localhost:8080/q/health
   ```

### Tools timeout

**Symptom**: AI reports tool execution timeouts

**Common Causes**:

1. **Long-running operations**
   - Log collection from many pods
   - Large time ranges
   - Complex metrics queries

   **Solution**: Use smaller time ranges or fewer pods
   ```
   Instead of: "Show me logs from the last 24 hours"
   Try: "Show me logs from the last hour"
   ```

2. **Slow Kubernetes API**
   - Large cluster with many resources
   - Network latency

   **Solution**: Use namespace filters
   ```
   Instead of: "List all Kafka clusters"
   Try: "List Kafka clusters in namespace kafka-ns"
   ```

3. **External service timeout** (Loki/Prometheus)
   ```bash
   # Check service connectivity
   kubectl exec -it <mcp-pod> -- curl http://loki.monitoring:3100/ready
   ```

## Log collection issues

### No logs returned

**Symptom**: Log collection tools return empty results

**Diagnosis**:

1. **Check pods exist**
   ```bash
   kubectl get pods -n kafka-ns -l strimzi.io/cluster=my-cluster
   ```

2. **Check pod logs directly**
   ```bash
   kubectl logs -n kafka-ns my-cluster-kafka-0 --tail=10
   ```

3. **Check time range**
   - Logs may be outside the requested time window
   - Try: "Show me logs from the last 5 minutes"

**Solutions**:

1. **Pods not found**: Verify cluster name and namespace
2. **No logs in time range**: Expand time window
3. **Container not specified**: Specify container name for multi-container pods

### Loki integration not working

**Symptom**: Logs always come from Kubernetes, never from Loki

**Diagnosis**:
```bash
# Check Loki configuration
kubectl -n streamshub-mcp exec <mcp-pod> -- env | grep LOKI

# Test Loki connectivity
kubectl -n streamshub-mcp exec <mcp-pod> -- \
  curl http://loki.monitoring:3100/ready
```

**Solutions**:

1. **Loki URL not configured**
   ```bash
   # Set environment variable
   kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
     LOKI_URL=http://loki.monitoring:3100
   ```

2. **Loki not accessible**
   ```bash
   # Check Loki service
   kubectl -n monitoring get svc loki
   
   # Check network policy
   kubectl -n streamshub-mcp get networkpolicy
   ```

3. **Authentication required**
   ```bash
   # Set auth token
   kubectl -n streamshub-mcp create secret generic loki-auth \
     --from-literal=token=your-token
   
   kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
     LOKI_AUTH_ENABLED=true \
     --from=secret/loki-auth
   ```

## Metrics issues

### No metrics returned

**Symptom**: Metrics queries return no data

**Diagnosis**:

1. **Check Prometheus configuration**
   ```bash
   kubectl -n streamshub-mcp exec <mcp-pod> -- env | grep PROMETHEUS
   ```

2. **Test Prometheus connectivity**
   ```bash
   kubectl -n streamshub-mcp exec <mcp-pod> -- \
     curl http://prometheus.monitoring:9090/-/ready
   ```

3. **Verify metrics exist**
   ```bash
   # Query Prometheus directly
   curl 'http://prometheus.monitoring:9090/api/v1/query?query=up'
   ```

**Solutions**:

1. **Prometheus not configured**: Set `PROMETHEUS_URL`
2. **Metrics not scraped**: Check Prometheus targets
   ```bash
   curl http://prometheus.monitoring:9090/api/v1/targets
   ```
3. **Wrong metric name**: Use Prometheus UI to find correct names

### Pod metrics scraping fails

**Symptom**: Direct pod metrics scraping returns errors

**Common Causes**:

1. **Missing RBAC permissions**
   ```
   Error: User cannot get pods/proxy
   ```
   
   **Solution**:
   ```bash
   # Deploy sensitive Role
   kubectl apply -f install/007-Role-sensitive.yaml -n kafka-ns
   
   # Create RoleBinding
   kubectl create rolebinding streamshub-mcp-sensitive \
     --role=streamshub-mcp-sensitive \
     --serviceaccount=streamshub-mcp:streamshub-mcp \
     -n kafka-ns
   ```

2. **Metrics endpoint not exposed**
   - Verify pod has metrics port configured
   - Check pod annotations for metrics path

## Performance issues

### Slow response times

**Symptom**: Tools take a long time to execute

**Solutions**:

1. **Use namespace filters**
   ```
   Instead of: "List all Kafka clusters"
   Try: "List Kafka clusters in namespace kafka-ns"
   ```

2. **Reduce time ranges**
   ```
   Instead of: "Show logs from the last 24 hours"
   Try: "Show logs from the last hour"
   ```

3. **Use Loki/Prometheus instead of direct queries**
   - Configure external services for better performance
   - Leverage pre-aggregated metrics

4. **Increase resource limits**
   ```bash
   kubectl -n streamshub-mcp set resources deployment/streamshub-strimzi-mcp \
     --limits=cpu=1000m,memory=1Gi \
     --requests=cpu=500m,memory=512Mi
   ```

### High memory usage

**Symptom**: MCP server pod using excessive memory

**Diagnosis**:
```bash
# Check memory usage
kubectl -n streamshub-mcp top pod

# Check for memory limits
kubectl -n streamshub-mcp get pod <pod-name> -o yaml | grep -A 5 resources
```

**Solutions**:

1. **Reduce response sizes**
   - Use smaller time ranges
   - Limit number of pods queried
   - Use pagination where available

2. **Increase memory limits**
   ```bash
   kubectl -n streamshub-mcp set resources deployment/streamshub-strimzi-mcp \
     --limits=memory=2Gi
   ```

## Getting help

### Enable debug logging

```bash
# For local development
export QUARKUS_LOG_LEVEL=DEBUG
export QUARKUS_LOG_CATEGORY__IO_STREAMSHUB_MCP__LEVEL=TRACE
./mvnw quarkus:dev

# For Kubernetes deployment
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  QUARKUS_LOG_LEVEL=DEBUG \
  QUARKUS_LOG_CATEGORY__IO_STREAMSHUB_MCP__LEVEL=TRACE
```

### Collect diagnostic information

```bash
# Server logs
kubectl -n streamshub-mcp logs <pod-name> --tail=100

# Server status
kubectl -n streamshub-mcp describe pod <pod-name>

# RBAC permissions
kubectl auth can-i --list --as=system:serviceaccount:streamshub-mcp:streamshub-mcp

# Configuration
kubectl -n streamshub-mcp get deployment streamshub-strimzi-mcp -o yaml
```

### Report issues

When reporting issues, include:

1. **Environment details**
   - Kubernetes version
   - Strimzi version
   - MCP server version
   - AI assistant (Claude Desktop, Claude Code, etc.)

2. **Error messages**
   - Complete error text
   - Server logs
   - AI assistant error messages

3. **Steps to reproduce**
   - What question you asked
   - What tools were called
   - Expected vs actual behavior

4. **Configuration**
   - Relevant environment variables
   - RBAC configuration
   - Network policies

**GitHub Issues**: https://github.com/streamshub/streamshub-mcp/issues

## Next steps

- **[Installation](installation.md)** — Reinstall or reconfigure
- **[Configuration](configuration.md)** — Adjust settings
- **[Usage Examples](usage-examples.md)** — See working examples

