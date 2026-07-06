// Shared kernel: every future module depends on these types directly (ApiException, AuditableEntity,
// ...), so it is exempt from Modulith's internal-package encapsulation by design, not by oversight.
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.fixhub.platform.common;
