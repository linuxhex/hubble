package com.ykc.hubble.vo;

import lombok.Data;
import java.util.List;

@Data
public class GatewayTrendVO {
    private List<String> timestamps;
    private List<Long> infoCounts;
    private List<Long> warnCounts;
    private List<Long> errorCounts;
}
