package com.anying.helpdesk.modelconfig.dto;

import com.anying.helpdesk.modelconfig.domain.ModelVendor;

public record ModelVendorResponse(
        ModelVendor code,
        String displayName,
        String baseUrl,
        boolean customBaseUrlRequired
) {

    public static ModelVendorResponse from(ModelVendor vendor) {
        return new ModelVendorResponse(
                vendor,
                vendor.getDisplayName(),
                vendor.getPresetBaseUrl(),
                vendor.isCustomBaseUrlRequired()
        );
    }
}
