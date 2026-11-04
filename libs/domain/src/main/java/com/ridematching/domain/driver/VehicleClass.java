package com.ridematching.domain.driver;

/**
 * 乘客可以请求的车型档位。在候选筛选中用作硬性过滤条件：
 * 请求 {@link #XL} 的乘客永远不会被派给 {@link #STANDARD} 车辆。
 */
public enum VehicleClass {

    /** 四个座位。默认档位。 */
    STANDARD(4),

    /** 六个及以上座位。 */
    XL(6),

    /** 四个座位，高端车辆。 */
    PREMIUM(4);

    private final int seats;

    VehicleClass(int seats) {
        this.seats = seats;
    }

    public int seats() {
        return seats;
    }

    /** 该档位的车辆能否满足对 {@code requested} 档位的请求。 */
    public boolean canServe(VehicleClass requested) {
        return this == requested;
    }
}
