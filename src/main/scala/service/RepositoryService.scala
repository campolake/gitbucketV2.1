package service

import model._
import simple._
import util.JGitUtil

trait RepositoryService { self: AccountService =>
  import RepositoryService._

  /**
   * Creates a new repository.
   *
   * @param repositoryName the repository name
   * @param userName the user name of the repository owner
   * @param description the repository description
   * @param isPrivate the repository type (private is true, otherwise false)
   * @param originRepositoryName specify for the forked repository. (default is None)
   * @param originUserName specify for the forked repository. (default is None)
   */
  def createRepository(repositoryName: String, userName: String, description: Option[String], isPrivate: Boolean,
                       originRepositoryName: Option[String] = None, originUserName: Option[String] = None,
                       parentRepositoryName: Option[String] = None, parentUserName: Option[String] = None)
                      (implicit s: Session): Unit = {
    Repositories insert
      Repository(
        userName             = userName,
        repositoryName       = repositoryName,
        isPrivate            = isPrivate,
        description          = description,
        defaultBranch        = "master",
        registeredDate       = currentDate,
        updatedDate          = currentDate,
        lastActivityDate     = currentDate,
        originUserName       = originUserName,
        originRepositoryName = originRepositoryName,
        parentUserName       = parentUserName,
        parentRepositoryName = parentRepositoryName)

    IssueId insert (userName, repositoryName, 0)
  }

  def renameRepository(oldUserName: String, oldRepositoryName: String, newUserName: String, newRepositoryName: String)
                      (implicit s: Session): Unit = {
    getAccountByUserName(newUserName).foreach { account =>
      (Repositories filter { t => t.byRepository(oldUserName, oldRepositoryName) } firstOption).map { repository =>
        Repositories insert repository.copy(userName = newUserName, repositoryName = newRepositoryName)

        val webHooks      = WebHooks     .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val milestones    = Milestones   .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val issueId       = IssueId      .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val issues        = Issues       .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val pullRequests  = PullRequests .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val labels        = Labels       .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val issueComments = IssueComments.filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val issueLabels   = IssueLabels  .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val activities    = Activities   .filter(_.byRepository(oldUserName, oldRepositoryName)).list
        val collaborators = Collaborators.filter(_.byRepository(oldUserName, oldRepositoryName)).list

        Repositories.filter { t =>
          (t.originUserName is oldUserName.bind) && (t.originRepositoryName is oldRepositoryName.bind)
        }.map { t => t.originUserName -> t.originRepositoryName }.update(newUserName, newRepositoryName)

        Repositories.filter { t =>
          (t.parentUserName is oldUserName.bind) && (t.parentRepositoryName is oldRepositoryName.bind)
        }.map { t => t.originUserName -> t.originRepositoryName }.update(newUserName, newRepositoryName)

        PullRequests.filter { t =>
          t.requestRepositoryName is oldRepositoryName.bind
        }.map { t => t.requestUserName -> t.requestRepositoryName }.update(newUserName, newRepositoryName)

        deleteRepository(oldUserName, oldRepositoryName)

        WebHooks      .insertAll(webHooks      .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        Milestones    .insertAll(milestones    .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        IssueId       .insertAll(issueId       .map(_.copy(_1       = newUserName, _2             = newRepositoryName)) :_*)
        Issues        .insertAll(issues        .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        PullRequests  .insertAll(pullRequests  .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        IssueComments .insertAll(issueComments .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        Labels        .insertAll(labels        .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        IssueLabels   .insertAll(issueLabels   .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        Activities    .insertAll(activities    .map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        if(account.isGroupAccount){
          Collaborators.insertAll(getGroupMembers(newUserName).map(m => Collaborator(newUserName, newRepositoryName, m.userName)) :_*)
        } else {
          Collaborators.insertAll(collaborators.map(_.copy(userName = newUserName, repositoryName = newRepositoryName)) :_*)
        }

        // Update activity messages
        val updateActivities = Activities.filter { t =>
          (t.message like s"%:${oldUserName}/${oldRepositoryName}]%") ||
          (t.message like s"%:${oldUserName}/${oldRepositoryName}#%")
        }.map { t => t.activityId -> t.message }.list

        updateActivities.foreach { case (activityId, message) =>
          Activities.filter(_.activityId is activityId.bind).map(_.message).update(
            message
              .replace(s"[repo:${oldUserName}/${oldRepositoryName}]"   ,s"[repo:${newUserName}/${newRepositoryName}]")
              .replace(s"[branch:${oldUserName}/${oldRepositoryName}#" ,s"[branch:${newUserName}/${newRepositoryName}#")
              .replace(s"[tag:${oldUserName}/${oldRepositoryName}#"    ,s"[tag:${newUserName}/${newRepositoryName}#")
              .replace(s"[pullreq:${oldUserName}/${oldRepositoryName}#",s"[pullreq:${newUserName}/${newRepositoryName}#")
              .replace(s"[issue:${oldUserName}/${oldRepositoryName}#"  ,s"[issue:${newUserName}/${newRepositoryName}#")
          )
        }
      }
    }
  }

  def deleteRepository(userName: String, repositoryName: String)(implicit s: Session): Unit = {
    Activities    .filter(_.byRepository(userName, repositoryName)).delete
    Collaborators .filter(_.byRepository(userName, repositoryName)).delete
    IssueLabels   .filter(_.byRepository(userName, repositoryName)).delete
    Labels        .filter(_.byRepository(userName, repositoryName)).delete
    IssueComments .filter(_.byRepository(userName, repositoryName)).delete
    PullRequests  .filter(_.byRepository(userName, repositoryName)).delete
    Issues        .filter(_.byRepository(userName, repositoryName)).delete
    IssueId       .filter(_.byRepository(userName, repositoryName)).delete
    Milestones    .filter(_.byRepository(userName, repositoryName)).delete
    WebHooks      .filter(_.byRepository(userName, repositoryName)).delete
    Repositories  .filter(_.byRepository(userName, repositoryName)).delete
  }

  /**
   * Returns the repository names of the specified user.
   *
   * @param userName the user name of repository owner
   * @return the list of repository names
   */
  def getRepositoryNamesOfUser(userName: String)(implicit s: Session): List[String] =
    Repositories filter(_.userName is userName.bind) map (_.repositoryName) list

  /**
   * Returns the specified repository information.
   * 
   * @param userName the user name of the repository owner
   * @param repositoryName the repository name
   * @param baseUrl the base url of this application
   * @return the repository information
   */
  def getRepository(userName: String, repositoryName: String, baseUrl: String)(implicit s: Session): Option[RepositoryInfo] = {
    (Repositories filter { t => t.byRepository(userName, repositoryName) } firstOption) map { repository =>
      // for getting issue count and pull request count
      val issues = Issues.filter { t =>
        t.byRepository(repository.userName, repository.repositoryName) && (t.closed is false.bind)
      }.map(_.pullRequest).list

      new RepositoryInfo(
        JGitUtil.getRepositoryInfo(repository.userName, repository.repositoryName, baseUrl),
        repository,
        issues.size,
        issues.filter(_ == true).size,
        getForkedCount(
          repository.originUserName.getOrElse(repository.userName),
          repository.originRepositoryName.getOrElse(repository.repositoryName)
        ),
        getRepositoryManagers(repository.userName))
    }
  }

  def getUserRepositories(userName: String, baseUrl: String, withoutPhysicalInfo: Boolean = false)
                         (implicit s: Session): List[RepositoryInfo] = {
    Repositories.filter { t1 =>
      (t1.userName is userName.bind) ||
        (Collaborators.filter { t2 => t2.byRepository(t1.userName, t1.repositoryName) && (t2.collaboratorName is userName.bind)} exists)
    }.sortBy(_.lastActivityDate desc).list.map{ repository =>
      new RepositoryInfo(
        if(withoutPhysicalInfo){
          new JGitUtil.RepositoryInfo(repository.userName, repository.repositoryName, baseUrl)
        } else {
          JGitUtil.getRepositoryInfo(repository.userName, repository.repositoryName, baseUrl)
        },
        repository,
        getForkedCount(
          repository.originUserName.getOrElse(repository.userName),
          repository.originRepositoryName.getOrElse(repository.repositoryName)
        ),
        getRepositoryManagers(repository.userName))
    }
  }

  /**
   * Returns the list of visible repositories for the specified user.
   * If repositoryUserName is given then filters results by repository owner.
   *
   * @param loginAccount the logged in account
   * @param baseUrl the base url of this application
   * @param repositoryUserName the repository owner (if None then returns all repositories which are visible for logged in user)
   * @param withoutPhysicalInfo if true then the result does not include physical repository information such as commit count,
   *                            branches and tags
   * @return the repository information which is sorted in descending order of lastActivityDate.
   */
  def getVisibleRepositories(loginAccount: Option[Account], baseUrl: String, repositoryUserName: Option[String] = None,
                             withoutPhysicalInfo: Boolean = false)
                            (implicit s: Session): List[RepositoryInfo] = {
    (loginAccount match {
      // for Administrators
      case Some(x) if(x.isAdmin) => Repositories
      // for Normal Users
      case Some(x) if(!x.isAdmin) =>
        Repositories filter { t => (t.isPrivate is false.bind) || (t.userName is x.userName) ||
          (Collaborators.filter { t2 => t2.byRepository(t.userName, t.repositoryName) && (t2.collaboratorName is x.userName.bind)} exists)
        }
      // for Guests
      case None => Repositories filter(_.isPrivate is false.bind)
    }).filter { t =>
      repositoryUserName.map { userName => t.userName is userName.bind } getOrElse LiteralColumn(true)
    }.sortBy(_.lastActivityDate desc).list.map{ repository =>
      new RepositoryInfo(
        if(withoutPhysicalInfo){
          new JGitUtil.RepositoryInfo(repository.userName, repository.repositoryName, baseUrl)
        } else {
          JGitUtil.getRepositoryInfo(repository.userName, repository.repositoryName, baseUrl)
        },
        repository,
        getForkedCount(
          repository.originUserName.getOrElse(repository.userName),
          repository.originRepositoryName.getOrElse(repository.repositoryName)
        ),
        getRepositoryManagers(repository.userName))
    }
  }

  private def getRepositoryManagers(userName: String)(implicit s: Session): Seq[String] =
    if(getAccountByUserName(userName).exists(_.isGroupAccount)){
      getGroupMembers(userName).collect { case x if(x.isManager) => x.userName }
    } else {
      Seq(userName)
    }

  /**
   * Updates the last activity date of the repository.
   */
  def updateLastActivityDate(userName: String, repositoryName: String)(implicit s: Session): Unit =
    Repositories.filter(_.byRepository(userName, repositoryName)).map(_.lastActivityDate).update(currentDate)

  /**
   * Save repository options.
   */
  def saveRepositoryOptions(userName: String, repositoryName: String, 
      description: Option[String], defaultBranch: String, isPrivate: Boolean)(implicit s: Session): Unit =
    Repositories.filter(_.byRepository(userName, repositoryName))
      .map { r => (r.description.?, r.defaultBranch, r.isPrivate, r.updatedDate) }
      .update (description, defaultBranch, isPrivate, currentDate)

  /**
   * Add collaborator to the repository.
   *
   * @param userName the user name of the repository owner
   * @param repositoryName the repository name
   * @param collaboratorName the collaborator name
   */
  def addCollaborator(userName: String, repositoryName: String, collaboratorName: String)(implicit s: Session): Unit =
    Collaborators insert Collaborator(userName, repositoryName, collaboratorName)

  /**
   * Remove collaborator from the repository.
   * 
   * @param userName the user name of the repository owner
   * @param repositoryName the repository name
   * @param collaboratorName the collaborator name
   */
  def removeCollaborator(userName: String, repositoryName: String, collaboratorName: String)(implicit s: Session): Unit =
    Collaborators.filter(_.byPrimaryKey(userName, repositoryName, collaboratorName)).delete

  /**
   * Remove all collaborators from the repository.
   *
   * @param userName the user name of the repository owner
   * @param repositoryName the repository name
   */
  def removeCollaborators(userName: String, repositoryName: String)(implicit s: Session): Unit =
    Collaborators.filter(_.byRepository(userName, repositoryName)).delete

  /**
   * Returns the list of collaborators name which is sorted with ascending order.
   * 
   * @param userName the user name of the repository owner
   * @param repositoryName the repository name
   * @return the list of collaborators name
   */
  def getCollaborators(userName: String, repositoryName: String)(implicit s: Session): List[String] =
    Collaborators.filter(_.byRepository(userName, repositoryName)).sortBy(_.collaboratorName).map(_.collaboratorName).list

  def hasWritePermission(owner: String, repository: String, loginAccount: Option[Account])(implicit s: Session): Boolean = {
    loginAccount match {
      case Some(a) if(a.isAdmin) => true
      case Some(a) if(a.userName == owner) => true
      case Some(a) if(getCollaborators(owner, repository).contains(a.userName)) => true
      case _ => false
    }
  }

  private def getForkedCount(userName: String, repositoryName: String)(implicit s: Session): Int =
    // TODO check SQL
    Query(Repositories.filter { t =>
      (t.originUserName is userName.bind) && (t.originRepositoryName is repositoryName.bind)
    }.length).first


  def getForkedRepositories(userName: String, repositoryName: String)(implicit s: Session): List[(String, String)] =
    Repositories.filter { t =>
      (t.originUserName is userName.bind) && (t.originRepositoryName is repositoryName.bind)
    }
    .sortBy(_.userName asc).map(t => t.userName -> t.repositoryName).list

}

object RepositoryService {

  case class RepositoryInfo(owner: String, name: String, httpUrl: String, repository: Repository,
    issueCount: Int, pullCount: Int, commitCount: Int, forkedCount: Int,
    branchList: Seq[String], tags: Seq[util.JGitUtil.TagInfo], managers: Seq[String]){

    lazy val host = """^https?://(.+?)(:\d+)?/""".r.findFirstMatchIn(httpUrl).get.group(1)

    def sshUrl(port: Int, userName: String) = s"ssh://${userName}@${host}:${port}/${owner}/${name}.git"

    /**
     * Creates instance with issue count and pull request count.
     */
    def this(repo: JGitUtil.RepositoryInfo, model: Repository, issueCount: Int, pullCount: Int, forkedCount: Int, managers: Seq[String]) =
      this(repo.owner, repo.name, repo.url, model, issueCount, pullCount, repo.commitCount, forkedCount, repo.branchList, repo.tags, managers)

    /**
     * Creates instance without issue count and pull request count.
     */
    def this(repo: JGitUtil.RepositoryInfo, model: Repository, forkedCount: Int, managers: Seq[String]) =
      this(repo.owner, repo.name, repo.url, model, 0, 0, repo.commitCount, forkedCount, repo.branchList, repo.tags, managers)
  }

  case class RepositoryTreeNode(owner: String, name: String, children: List[RepositoryTreeNode])

}