# Preserve one auditable output boundary after R8 and when SDK AARs are consumed.
-keep class **.logging.PropertyLog { *; }
-keep class **.logging.PropertyLog$* { *; }
