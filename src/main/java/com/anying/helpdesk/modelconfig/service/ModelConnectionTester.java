package com.anying.helpdesk.modelconfig.service;

import com.anying.helpdesk.modelconfig.domain.AiModelConfig;

public interface ModelConnectionTester {

    void test(AiModelConfig config);
}
