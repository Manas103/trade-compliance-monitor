package staging;

import java.util.Optional;

/**
 * Unlike compliance.Rule (which flags an already-executed trade), a
 * StagingRule judges a proposed order before it is released: returning a
 * reason means "block", returning empty means "this rule has no objection".
 */
public interface StagingRule {
    String name();

    Optional<String> check(Order order, PmBook book);
}
