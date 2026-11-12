package vn.edu.finalism.logistics.shared;

import java.time.Instant;
import java.util.List;

public record PageResult<T>(List<T> items, String nextCursor, Instant asOf) {
}
