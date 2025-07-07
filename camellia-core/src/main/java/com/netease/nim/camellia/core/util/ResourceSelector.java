package com.netease.nim.camellia.core.util;

import com.netease.nim.camellia.core.client.env.ProxyEnv;
import com.netease.nim.camellia.core.model.Resource;
import com.netease.nim.camellia.core.model.ResourceTable;
import com.netease.nim.camellia.core.model.operation.ResourceOperation;
import com.netease.nim.camellia.core.model.operation.ResourceReadOperation;
import com.netease.nim.camellia.core.model.operation.ResourceWriteOperation;
import com.netease.nim.camellia.tools.utils.MathUtil;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 *
 * Created by caojiajun on 2019/12/13.
 */
public class ResourceSelector {

    public static final byte[] EMPTY_ARRAY = new byte[0];

    private final ResourceTable resourceTable;
    private final ProxyEnv proxyEnv;

    private ReadResources readResources = null;
    private Resource readResource = null;
    private List<Resource> writeResources = null;

    private boolean bucketSizeIs2Power = false;

    private final Set<Resource> allResources;
    private final List<Resource> allReadResources;
    private final List<Resource> allWriteResources;
    private final boolean isMultiReadMode;

    private final long createTime = System.currentTimeMillis();

    private final ResourceChecker resourceChecker;

    public ResourceSelector(ResourceTable resourceTable, ProxyEnv proxyEnv) {
        this(resourceTable, proxyEnv, null);
    }

    public ResourceSelector(ResourceTable resourceTable, ProxyEnv proxyEnv, ResourceSelector.ResourceChecker resourceChecker) {
        this.resourceTable = ResourceTableUtil.immutableResourceTable(resourceTable);
        this.proxyEnv = proxyEnv;
        this.resourceChecker = resourceChecker;
        ResourceTable.Type type = resourceTable.getType();
        if (type == ResourceTable.Type.SHADING) {
            int bucketSize = resourceTable.getShadingTable().getBucketSize();
            bucketSizeIs2Power = MathUtil.is2Power(bucketSize);
        }
        //all
        this.allResources = Collections.unmodifiableSet(ResourceUtil.getAllResources(resourceTable));
        //read
        Set<Resource> readResources = new TreeSet<>(Comparator.comparing(Resource::getUrl));
        readResources.addAll(ResourceUtil.getAllReadResources(resourceTable));
        this.allReadResources = Collections.unmodifiableList(new ArrayList<>(readResources));
        //write
        Set<Resource> writeResources = new TreeSet<>(Comparator.comparing(Resource::getUrl));
        writeResources.addAll(ResourceUtil.getAllWriteResources(resourceTable));
        this.allWriteResources = Collections.unmodifiableList(new ArrayList<>(writeResources));
        this.isMultiReadMode = isMultiReadMode(resourceTable);
        //
        if (resourceChecker != null && isMultiReadMode) {
            for (Resource resource : allReadResources) {
                resourceChecker.addResource(resource);
            }
        }
    }

    public ResourceTable getResourceTable() {
        return resourceTable;
    }

    public ResourceChecker getResourceChecker() {
        return resourceChecker;
    }

    public ProxyEnv getProxyEnv() {
        return proxyEnv;
    }

    public long getCreateTime() {
        return createTime;
    }

    public ResourceTable.Type getType() {
        return resourceTable.getType();
    }

    public List<Resource> getAllReadResources() {
        return allReadResources;
    }

    public List<Resource> getReadResourcesWithCheckMultiRead() {
        List<Set<Resource>> list = ResourceUtil.getAllReadResourceList(resourceTable);
        Set<Resource> set = new HashSet<>();
        for (Set<Resource> resources : list) {
            //有重复的，直接跳过
            if (!set.isEmpty()) {
                boolean skip = false;
                for (Resource resource : resources) {
                    if (set.contains(resource)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
            }
            //否则只加1个
            boolean add = false;
            for (Resource resource : resources) {
                if (resourceChecker != null) {
                    if (resourceChecker.checkValid(resource)) {
                        set.add(resource);
                        add = true;
                        break;
                    }
                } else {
                    set.add(resource);
                    add = true;
                    break;
                }
            }
            //至少加1个
            if (!add) {
                for (Resource resource : resources) {
                    set.add(resource);
                    break;
                }
            }
        }
        List<Resource> result = new ArrayList<>(set);
        result.sort(Comparator.comparing(Resource::getUrl));
        return result;
    }

    public List<Resource> getAllWriteResources() {
        return allWriteResources;
    }

    public Set<Resource> getAllResources() {
        return allResources;
    }

    public static interface ResourceChecker {
        void addResource(Resource resource);
        boolean checkValid(Resource resource);
    }

    /**
     * 获取read resource
     * @param shadingKey shadingKey
     * @return resource
     */
    public Resource getReadResource(byte[] shadingKey) {
        if (readResource != null) {
            return readResource;
        }
        ResourceTable.Type type = resourceTable.getType();
        ReadResources readResources = getReadResources(shadingKey);
        if (readResources == null) {
            return null;
        }
        Resource readResource = getReadResource(readResources);
        if (type == ResourceTable.Type.SIMPLE && readResources.getResources().size() == 1) {
            //下次就可以走缓存了
           this.readResource = readResource;
        }
        return readResource;
    }

    /**
     * 获取read resource 并且判断shadingKeys对于的resource是否一致，如果不一致，则返回null
     * @param shadingKeys shadingKeys
     * @return resource
     */
    public Resource getReadResourceWithCheckEqual(List<byte[]> shadingKeys) {
        ReadResources resources = null;
        for (byte[] key : shadingKeys) {
            ReadResources nextResources = getReadResources(key);
            if (resources != null) {
                boolean checkReadResourcesEqual = getReadResourceWithCheckEqual(resources, nextResources);
                if (!checkReadResourcesEqual) {
                    return null;
                }
            }
            resources = nextResources;
        }
        return getReadResource(resources);
    }

    /**
     * 获取write resource
     * @param shardingParam shardingParam
     * @return resource
     */
    public List<Resource> getWriteResources(byte[]... shardingParam) {
        if (writeResources != null) return writeResources;
        ResourceTable.Type type = resourceTable.getType();
        if (type == ResourceTable.Type.SIMPLE) {
            ResourceTable.SimpleTable simpleTable = resourceTable.getSimpleTable();
            ResourceOperation resourceOperation = simpleTable.getResourceOperation();
            this.writeResources = getWriteResourcesFromOperation(resourceOperation);
            return this.writeResources;
        } else if (type == ResourceTable.Type.SHADING) {
            int shardingCode = proxyEnv.getShardingFunc().shardingCode(shardingParam);
            ResourceTable.ShadingTable shardingTable = resourceTable.getShadingTable();
            int bucketSize = shardingTable.getBucketSize();
            Map<Integer, ResourceOperation> operationMap = shardingTable.getResourceOperationMap();
            int index = MathUtil.mod(bucketSizeIs2Power, Math.abs(shardingCode), bucketSize);
            ResourceOperation resourceOperation = operationMap.get(index);
            return getWriteResourcesFromOperation(resourceOperation);
        }
        throw new IllegalArgumentException();
    }

    /**
     * 获取write resource list 并且判断shadingKeys对于的resource是否一致，如果不一致，则返回null
     * @param shadingKeys shadingKeys
     * @return resource
     */
    public List<Resource> getWriteResourcesWithCheckEqual(List<byte[]> shadingKeys) {
        List<Resource> resources = null;
        for (byte[] key : shadingKeys) {
            List<Resource> nextResources = getWriteResources(key);
            if (resources != null) {
                boolean checkWriteResourcesEqual = checkWriteResourcesEqual(resources, nextResources);
                if (!checkWriteResourcesEqual) {
                    return null;
                }
            }
            resources = nextResources;
        }
        return resources;
    }

    /**
     * isMultiReadMode
     * @return true/false
     */
    public boolean isMultiReadMode() {
        return isMultiReadMode;
    }

    private Resource getReadResource(ReadResources readResources) {
        if (readResources == null) return null;
        List<Resource> resources = readResources.getValidResource();
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        if (resources.size() == 1) {
            return resources.get(0);
        }
        if (readResources.getType() == ResourceReadOperation.Type.SIMPLE || readResources.getType() == ResourceReadOperation.Type.ORDER) {
            return resources.get(0);
        } else if (readResources.getType() == ResourceReadOperation.Type.RANDOM) {
            int index = ThreadLocalRandom.current().nextInt(resources.size());
            return resources.get(index);
        }
        return null;
    }

    private ReadResources getReadResources(byte[] shardingKey) {
        if (readResources != null) {//get from cache
            return readResources;
        }
        ResourceTable.Type type = resourceTable.getType();
        if (type == ResourceTable.Type.SIMPLE) {
            ResourceTable.SimpleTable simpleTable = resourceTable.getSimpleTable();
            ResourceOperation resourceOperation = simpleTable.getResourceOperation();
            ReadResources readResourceBean = getReadResourcesFromOperation(resourceOperation);
            if (!readResourceBean.isDynamic()) {
                this.readResources = readResourceBean;
            }
            return readResourceBean;
        } else if (type == ResourceTable.Type.SHADING) {
            int shardingCode = proxyEnv.getShardingFunc().shardingCode(shardingKey);
            ResourceTable.ShadingTable shardingTable = resourceTable.getShadingTable();
            int bucketSize = shardingTable.getBucketSize();
            Map<Integer, ResourceOperation> operationMap = shardingTable.getResourceOperationMap();
            int index = MathUtil.mod(bucketSizeIs2Power, Math.abs(shardingCode), bucketSize);
            ResourceOperation resourceOperation = operationMap.get(index);
            return getReadResourcesFromOperation(resourceOperation);
        }
        throw new IllegalArgumentException();
    }

    private ReadResources getReadResourcesFromOperation(ResourceOperation resourceOperation) {
        ResourceOperation.Type resourceOperationType = resourceOperation.getType();
        if (resourceOperationType == ResourceOperation.Type.SIMPLE) {
            return new ReadResources(resourceChecker, ResourceReadOperation.Type.SIMPLE, Collections.singletonList(resourceOperation.getResource()));
        } else if (resourceOperationType == ResourceOperation.Type.RW_SEPARATE) {
            ResourceReadOperation readOperation = resourceOperation.getReadOperation();
            ResourceReadOperation.Type readOperationType = readOperation.getType();
            if (readOperationType == ResourceReadOperation.Type.SIMPLE) {
                return new ReadResources(resourceChecker, readOperationType, Collections.singletonList(readOperation.getReadResource()));
            } else if (readOperationType == ResourceReadOperation.Type.ORDER || readOperationType == ResourceReadOperation.Type.RANDOM) {
                List<Resource> readResources = readOperation.getReadResources();
                return new ReadResources(resourceChecker, readOperationType, readResources);
            }
        }
        throw new IllegalArgumentException();
    }

    private List<Resource> getWriteResourcesFromOperation(ResourceOperation resourceOperation) {
        ResourceOperation.Type resourceOperationType = resourceOperation.getType();
        if (resourceOperationType == ResourceOperation.Type.SIMPLE) {
            return Collections.singletonList(resourceOperation.getResource());
        } else if (resourceOperationType == ResourceOperation.Type.RW_SEPARATE) {
            ResourceWriteOperation writeOperation = resourceOperation.getWriteOperation();
            ResourceWriteOperation.Type writeOperationType = writeOperation.getType();
            if (writeOperationType == ResourceWriteOperation.Type.SIMPLE) {
                return Collections.singletonList(writeOperation.getWriteResource());
            } else if (writeOperationType == ResourceWriteOperation.Type.MULTI) {
                return writeOperation.getWriteResources();
            }
        }
        throw new IllegalArgumentException();
    }

    private boolean getReadResourceWithCheckEqual(ReadResources readResources1, ReadResources readResources2) {
        if (readResources1 == null || readResources2 == null) {
            return false;
        }
        if (readResources1.getType() != readResources2.getType()) {
            return false;
        }
        if (readResources1.getResources() == null || readResources2.getResources() == null) {
            return false;
        }
        if (readResources1.getResources().size() != readResources2.getResources().size()) {
            return false;
        }
        for (int i = 0; i < readResources1.getResources().size(); i++) {
            Resource resource1 = readResources1.getResources().get(i);
            Resource resource2 = readResources2.getResources().get(i);
            if (!resource1.getUrl().equals(resource2.getUrl())) {
                return false;
            }
        }
        return true;
    }

    private boolean checkWriteResourcesEqual(List<Resource> resources1, List<Resource> resources2) {
        if (resources1 == null || resources2 == null) {
            return false;
        }
        if (resources1.size() != resources2.size()) {
            return false;
        }
        for (int i = 0; i < resources1.size(); i++) {
            Resource resource1 = resources1.get(i);
            Resource resource2 = resources2.get(i);
            if (!resource1.getUrl().equals(resource2.getUrl())) {
                return false;
            }
        }
        return true;
    }

    private boolean isMultiReadMode(ResourceTable resourceTable) {
        ResourceTable.Type type = resourceTable.getType();
        if (type == ResourceTable.Type.SIMPLE) {
            ResourceTable.SimpleTable simpleTable = resourceTable.getSimpleTable();
            ResourceOperation resourceOperation = simpleTable.getResourceOperation();
            ReadResources readResources = getReadResourcesFromOperation(resourceOperation);
            return readResources.getResources().size() > 1;
        } else if (type == ResourceTable.Type.SHADING) {
            ResourceTable.ShadingTable shadingTable = resourceTable.getShadingTable();
            for (ResourceOperation resourceOperation : shadingTable.getResourceOperationMap().values()) {
                ReadResources readResources = getReadResourcesFromOperation(resourceOperation);
                if (readResources.getResources().size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    public static class ReadResources {
        private final ResourceReadOperation.Type type;
        private final List<Resource> resources;
        private List<Resource> validResources;
        private boolean dynamic = false;

        public ReadResources(ResourceChecker resourceChecker, ResourceReadOperation.Type type, List<Resource> resources) {
            this.type = type;
            this.resources = resources;
            this.validResources = resources;
            if (resourceChecker != null && resources.size() > 1) {
                this.validResources = new ArrayList<>();
                for (Resource resource : resources) {
                    if (resourceChecker.checkValid(resource)) {
                        validResources.add(resource);
                    }
                }
                if (validResources.isEmpty()) {
                    validResources = resources;
                }
                dynamic = true;
            }
        }

        public ResourceReadOperation.Type getType() {
            return type;
        }

        public List<Resource> getResources() {
            return resources;
        }

        public List<Resource> getValidResource() {
            return validResources;
        }

        public boolean isDynamic() {
            return dynamic;
        }

    }
}
