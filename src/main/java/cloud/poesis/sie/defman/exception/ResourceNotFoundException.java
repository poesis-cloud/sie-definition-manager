package cloud.poesis.sie.defman.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A referenced resource (Definition, Ascription, or other entity) does not
 * exist — the lookup by identifier returned no result.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final UUID resourceId;

    public ResourceNotFoundException(String resourceType, UUID resourceId) {
        super(resourceType + " not found: " + resourceId);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getType() {
        return "gsm:exceptions/resource-not-found";
    }

    public String getTitle() {
        return "Not found";
    }

    public Map<String, Object> getExtensions() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (resourceType != null) {
            map.put("resourceType", resourceType);
        }
        if (resourceId != null) {
            map.put("resourceId", resourceId);
        }
        return Map.copyOf(map);
    }
}
