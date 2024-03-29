package model

import slick.driver.JdbcProfile

trait Profile {
  val profile: JdbcProfile
  import profile.simple._

  // java.util.Date Mapped Column Types
  implicit val dateColumnType = MappedColumnType.base[java.util.Date, java.sql.Timestamp](
      d => new java.sql.Timestamp(d.getTime),
      t => new java.util.Date(t.getTime)
  )

  implicit class RichColumn(c1: Column[Boolean]){
    def &&(c2: => Column[Boolean], guard: => Boolean): Column[Boolean] = if(guard) c1 && c2 else c1
  }

}
