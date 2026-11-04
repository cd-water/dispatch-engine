package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;

/** 不存在所请求 id 的行程。以 {@code 404} 呈现。 */
public class TripNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TripNotFoundException(RideId rideId) {
        super("No trip with id " + rideId);
    }
}
