package com.researchspace.service.impl;

import static com.researchspace.model.comms.NotificationType.NOTIFICATION_DOCUMENT_SHARED;
import static com.researchspace.model.comms.NotificationType.NOTIFICATION_DOCUMENT_UNSHARED;
import static com.researchspace.service.CommunicationNotifyPolicy.ALWAYS_NOTIFY;
import static java.util.stream.Collectors.toSet;

import com.researchspace.core.util.ISearchResults;
import com.researchspace.core.util.TransformerUtils;
import com.researchspace.dao.CommunicationDao;
import com.researchspace.dao.FolderDao;
import com.researchspace.dao.GroupDao;
import com.researchspace.dao.RecordDao;
import com.researchspace.dao.RecordGroupSharingDao;
import com.researchspace.dao.UserDao;
import com.researchspace.model.AbstractUserOrGroupImpl;
import com.researchspace.model.Community;
import com.researchspace.model.EcatMediaFile;
import com.researchspace.model.FieldAttachment;
import com.researchspace.model.Group;
import com.researchspace.model.PaginationCriteria;
import com.researchspace.model.RecordGroupSharing;
import com.researchspace.model.Role;
import com.researchspace.model.RoleInGroup;
import com.researchspace.model.User;
import com.researchspace.model.comms.CommunicationStatus;
import com.researchspace.model.comms.ShareRecordMessageOrRequest;
import com.researchspace.model.core.RecordType;
import com.researchspace.model.core.UniquelyIdentifiable;
import com.researchspace.model.dtos.ShareConfigElement;
import com.researchspace.model.field.ErrorList;
import com.researchspace.model.field.Field;
import com.researchspace.model.permissions.ACLElement;
import com.researchspace.model.permissions.ConstraintBasedPermission;
import com.researchspace.model.permissions.DefaultPermissionFactory;
import com.researchspace.model.permissions.IPermissionUtils;
import com.researchspace.model.permissions.IdConstraint;
import com.researchspace.model.permissions.PermissionDomain;
import com.researchspace.model.permissions.PermissionFactory;
import com.researchspace.model.permissions.PermissionType;
import com.researchspace.model.permissions.RecordSharingACL;
import com.researchspace.model.record.ACLPropagationPolicy;
import com.researchspace.model.record.BaseRecord;
import com.researchspace.model.record.BaseRecord.SharedStatus;
import com.researchspace.model.record.ChildAddPolicy;
import com.researchspace.model.record.Folder;
import com.researchspace.model.record.IRecordFactory;
import com.researchspace.model.record.IllegalAddChildOperation;
import com.researchspace.model.record.Notebook;
import com.researchspace.model.record.ObjectToIdPropertyTransformer;
import com.researchspace.model.record.RSPath;
import com.researchspace.model.record.Record;
import com.researchspace.model.views.ServiceOperationResult;
import com.researchspace.properties.IPropertyHolder;
import com.researchspace.service.CommunicationManager;
import com.researchspace.service.CommunityServiceManager;
import com.researchspace.service.DocumentSharedStateCalculator;
import com.researchspace.service.FolderManager;
import com.researchspace.service.IContentInitializer;
import com.researchspace.service.NotificationConfig;
import com.researchspace.service.RecordSharingManager;
import com.researchspace.service.ShareRecordMessageOrRequestDTO;
import com.researchspace.service.UserFolderCreator;
import com.researchspace.service.UserManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("recordSharing")
public class RecordSharingManagerImpl implements RecordSharingManager {

  private static Logger log = LoggerFactory.getLogger(RecordSharingManagerImpl.class);

  private IContentInitializer contentInitializer;
  private IRecordFactory recFactory;
  @Autowired private CommunityServiceManager communityService;
  @Autowired private UserContentUpdater userContentUpdater;

  @Autowired
  public void setRecFactory(IRecordFactory recFactory) {
    this.recFactory = recFactory;
  }

  private PermissionFactory perFactory = new DefaultPermissionFactory();

  @Autowired
  void setContentInitializer(IContentInitializer contentInitializer) {
    this.contentInitializer = contentInitializer;
  }

  private @Autowired RecordDao recordDao;

  private @Autowired RecordGroupSharingDao groupshareRecordDao;
  private @Autowired IPermissionUtils permissnUtils;
  private @Autowired UserDao userDao;
  private @Autowired GroupDao grpDao;
  private @Autowired CommunicationDao communicationDao;
  private @Autowired FolderDao folderDao;
  private @Autowired CommunicationManager commMgr;
  private @Autowired FolderManager folderMgr;
  private @Autowired UserManager userManager;
  private @Autowired DocumentSharedStateCalculator docSharedStatusCalculator;
  private @Autowired IPropertyHolder properties;
  private PermissionFactory permFac = new DefaultPermissionFactory();

  @Override
  public RecordGroupSharing get(Long id) {
    return groupshareRecordDao.get(id);
  }

  @Override
  public RecordGroupSharing getByPublicLink(String publicLink) {
    return groupshareRecordDao.getRecordWithPublicLink(publicLink);
  }

  @Override // note this groups by recordID - if a record has been shared AND published we will only
  // see ONE of the RGS records
  public List<RecordGroupSharing> getSharedRecordsForUser(User u) {
    return groupshareRecordDao.getSharedRecordsForUser(u);
  }

  @Override
  public ISearchResults<RecordGroupSharing> listSharedRecordsForUser(
      User u, PaginationCriteria<RecordGroupSharing> pcg) {
    ISearchResults<RecordGroupSharing> sharedForUser =
        groupshareRecordDao.listSharedRecordsForUser(u, pcg);
    populateSharingPermissionType(sharedForUser.getResults());
    return sharedForUser;
  }

  @Override
  public ISearchResults<RecordGroupSharing>
      listPublishedRecordsOwnedByUserOrGroupMembersPlusRecordsPublishedByUser(
          User u, PaginationCriteria<RecordGroupSharing> pcg) {
    Set<Group> usersGroups = u.getGroups();
    List<Group> groupsWhereUserIsPi = new ArrayList<>();
    List<Long> membersOfUsersGroups = new ArrayList<>();
    for (Group userGroup : usersGroups) {
      if (u.hasRoleInGroup(userGroup, RoleInGroup.PI)) {
        groupsWhereUserIsPi.add(userGroup);
      }
    }
    for (Group userGroup : groupsWhereUserIsPi) {
      for (User aUser : userGroup.getMembers()) {
        membersOfUsersGroups.add(aUser.getId());
      }
    }
    ISearchResults<RecordGroupSharing> sharedForUser =
        groupshareRecordDao.listPublishedRecordsOwnedByUserOrGroupMembersPlusRecordsPublishedByUser(
            u, pcg, membersOfUsersGroups);
    populateSharingPermissionType(sharedForUser.getResults());
    return sharedForUser;
  }

  @Override
  public ISearchResults<RecordGroupSharing> listUserRecordsPublished(
      User u, PaginationCriteria<RecordGroupSharing> pcg) {
    ISearchResults<RecordGroupSharing> sharedForUser =
        groupshareRecordDao.listUserRecordsPublished(u, pcg);
    populateSharingPermissionType(sharedForUser.getResults());
    return sharedForUser;
  }

  @Override
  public ISearchResults<RecordGroupSharing> listAllPublishedRecords(
      PaginationCriteria<RecordGroupSharing> pcg) {
    ISearchResults<RecordGroupSharing> rc = groupshareRecordDao.listAllPublishedRecords(pcg);
    populateSharingPermissionType(rc.getResults());
    return rc;
  }

  @Override
  public ISearchResults<RecordGroupSharing> listAllPublishedRecordsForInternet(
      PaginationCriteria<RecordGroupSharing> pagCrit) {
    return groupshareRecordDao.listAllPublishedRecordsForInternet(pagCrit);
  }

  @Override
  public ISearchResults<RecordGroupSharing> listAllRecordsPublishedByMembersOfAdminsCommunities(
      PaginationCriteria<RecordGroupSharing> pcg, User commAdmin) {
    List<Community> communitiesManagedBy =
        communityService.listCommunitiesForAdmin(commAdmin.getId());
    ISearchResults<RecordGroupSharing> rc =
        groupshareRecordDao.listAllRecordsPublishedByCommunityMembers(pcg, communitiesManagedBy);
    populateSharingPermissionType(rc.getResults());
    return rc;
  }

  @Override
  public List<RecordGroupSharing> getSharedRecordsForUserAndGroup(User user, Group group) {
    List<RecordGroupSharing> shared =
        groupshareRecordDao.getRecordsSharedByUserToGroup(user, group);
    populateSharingPermissionType(shared);
    return shared;
  }

  @Override
  public List<BaseRecord> getTemplatesSharedWithUser(User u) {
    return groupshareRecordDao.getSharedTemplatesWithUser(u);
  }

  @Override
  public List<RecordGroupSharing> getRecordSharingInfo(Long recordId) {
    List<RecordGroupSharing> sharingList =
        groupshareRecordDao.getRecordGroupSharingsForRecord(recordId);
    populateSharingPermissionType(sharingList);
    return sharingList;
  }

  private void populateSharingPermissionType(List<RecordGroupSharing> sharingList) {
    for (RecordGroupSharing rgs : sharingList) {
      AbstractUserOrGroupImpl sharee = rgs.getSharee();
      BaseRecord record = rgs.getShared();
      ConstraintBasedPermission cbp =
          permissnUtils.findBy(
              sharee.getPermissions(),
              PermissionDomain.RECORD,
              new IdConstraint(record.getId()),
              TransformerUtils.toList(PermissionType.WRITE, PermissionType.READ));
      rgs.setPermType(cbp.getActions().iterator().next());
    }
  }

  @Override
  public ServiceOperationResult<List<RecordGroupSharing>> shareRecord(
      User subject, Long recordToShareId, ShareConfigElement[] sharingConfigs)
      throws IllegalAddChildOperation {

    BaseRecord recordOrNotebook = getRecordOrNotebook(recordToShareId);
    if (recordOrNotebook.isMediaRecord()) {
      throw new AuthorizationException(
          String.format(
              "Can't share  record %s, can only share notebooks or documents or snippets",
              recordOrNotebook.getGlobalIdentifier()));
    }
    if (recordOrNotebook.isFolder() && !recordOrNotebook.isNotebook()) {
      throw new AuthorizationException("Cannot share a folder :" + recordOrNotebook.getName());
    }
    boolean piSharingOnlyWithAnyonymous = false;
    // we dont mix sharing and publishing in the same 'share' action
    boolean publishing =
        sharingConfigs[0].getUserId() != null
            && userManager.get(sharingConfigs[0].getUserId()).hasRole(Role.ANONYMOUS_ROLE);

    if (sharingConfigs.length == 1) {
      piSharingOnlyWithAnyonymous = subject.hasRole(Role.PI_ROLE) && publishing;
    }
    if (!piSharingOnlyWithAnyonymous && !subject.isOwnerOfRecord(recordOrNotebook)) {
      throw new AuthorizationException(
          "Only document owner can share " + recordOrNotebook.getName());
    }

    List<RecordGroupSharing> shared =
        doRecordOrNotebookShare(subject, recordOrNotebook, sharingConfigs, true);
    // don't notify if autoshare is set
    // we dont mix sharing and publishing in the same 'share' action
    if (!shared.isEmpty() && !isAutoshare(sharingConfigs) && !publishing) {
      NotificationConfig cfg =
          NotificationConfig.builder()
              .notificationType(NOTIFICATION_DOCUMENT_SHARED)
              .policyOverride(ALWAYS_NOTIFY)
              .recordAuthorisationRequired(true)
              .notificationTargetsOverride(getSharedToNotify(shared))
              .broadcast(true)
              .build();

      commMgr.notify(
          subject, recordOrNotebook, cfg, createSharingMessage(subject, recordOrNotebook));
    }
    return new ServiceOperationResult<>(shared, !shared.isEmpty());
  }

  private String createSharingMessage(User subject, BaseRecord recordOrNotebook) {
    return String.format("%s shared by %s", recordOrNotebook.getName(), subject.getUsername());
  }

  // looks at 1st config element to see if it is autoshare context.
  private boolean isAutoshare(ShareConfigElement[] sharingConfigs) {
    if (sharingConfigs.length == 0 || sharingConfigs[0] == null) {
      return false;
    }
    return sharingConfigs[0].isAutoshare();
  }

  @Override
  public void unshareRecord(
      User unsharing, Long recordToUnshareId, ShareConfigElement[] groupIdsToUnshareWith)
      throws IllegalAddChildOperation {

    BaseRecord recordOrNotebook = getRecordOrNotebook(recordToUnshareId);
    List<RecordGroupSharing> unshared =
        doRecordOrNotebookShare(unsharing, recordOrNotebook, groupIdsToUnshareWith, false);
    if (!unshared.isEmpty() && !isAutoshare(groupIdsToUnshareWith)) {
      NotificationConfig cfg =
          NotificationConfig.builder()
              .notificationType(NOTIFICATION_DOCUMENT_UNSHARED)
              .broadcast(true)
              .policyOverride(ALWAYS_NOTIFY)
              .notificationTargetsOverride(getUnsharedToNotify(unshared))
              .recordAuthorisationRequired(false)
              .build();
      commMgr.notify(
          unsharing, recordOrNotebook, cfg, createUnsharingMsg(unsharing, recordOrNotebook));
    }
  }

  private String createUnsharingMsg(User unsharing, BaseRecord recordOrNotebook) {
    return String.format(
        "%s stopped sharing %s (%s)",
        unsharing.getUsername(),
        recordOrNotebook.getName(),
        recordOrNotebook.getGlobalIdentifier());
  }

  // gets those users affected by unshare who can no longer see the document
  private Set<User> getSharedToNotify(List<RecordGroupSharing> shared) {
    return doGetUsersToNotify(shared, g -> Collections.emptySet());
  }

  // gets those users affected by unshare who can no longer see the document
  private Set<User> getUnsharedToNotify(List<RecordGroupSharing> unshared) {
    Function<Group, Set<User>> modulator = Group::getMembersWithDefaultViewAllPermissions;
    return doGetUsersToNotify(unshared, modulator);
  }

  private Set<User> doGetUsersToNotify(
      List<RecordGroupSharing> shareDelta, Function<Group, Set<User>> userModulator) {
    Set<User> toNotify = new HashSet<>();
    Set<AbstractUserOrGroupImpl> inputToNotify =
        shareDelta.stream().map(RecordGroupSharing::getSharee).collect(toSet());
    for (AbstractUserOrGroupImpl aug : inputToNotify) {
      if (aug.isUser()) {
        toNotify.add((User) aug);
      } else {
        Group grp = (Group) aug;
        Set<User> allMembers = new HashSet<>(grp.getMembers());
        allMembers.removeAll(userModulator.apply(grp));
        toNotify.addAll(allMembers);
      }
    }
    return toNotify;
  }

  @Override
  public ErrorList updatePermissionForRecord(Long recordGroupSharing, String action, String uname) {
    RecordGroupSharing rgs = null;
    PermissionType type = null;

    rgs = get(recordGroupSharing);
    type = permissnUtils.createFromString(action);

    AbstractUserOrGroupImpl userOrGroup = rgs.getSharee();
    ConstraintBasedPermission toUpdate =
        permissnUtils.findBy(
            userOrGroup.getPermissions(),
            PermissionDomain.RECORD,
            new IdConstraint(rgs.getShared().getId()));
    if (toUpdate != null) {
      userOrGroup.removePermission(toUpdate);

      ConstraintBasedPermission newPerm =
          perFactory.createIdPermission(PermissionDomain.RECORD, type, rgs.getShared().getId());
      userOrGroup.addPermission(newPerm);
      saveUserOrGroup(userOrGroup);
      // forces other group members to refresh cache
      log.info("Notifying {} to refresh permissions cache ", userOrGroup.getUniqueName());
      permissnUtils.notifyUserOrGroupToRefreshCache(userOrGroup);
      permissnUtils.refreshCacheIfNotified();
      updateACLPermissions(rgs, userOrGroup, toUpdate, newPerm);
      return null;
    } else {
      ErrorList el = new ErrorList();
      el.addErrorMsg("Could not update permission");
      return el;
    }
  }

  @Override
  public void updateSharedStatusOfRecords(Collection<? extends BaseRecord> records, User subject) {

    List<Long> recordIds =
        records.stream().map(new ObjectToIdPropertyTransformer()).collect(Collectors.toList());
    List<Long> sharedIds = groupshareRecordDao.findSharedRecords(recordIds);

    for (BaseRecord record : records) {
      if (sharedIds.contains(record.getId())) {
        record.setSharedStatus(SharedStatus.SHARED);
      } else {
        record.setSharedStatus(SharedStatus.UNSHARED);
      }
    }
  }

  @Override
  public List<ShareRecordMessageOrRequestDTO> getSharedRecordRequestsByUserId(Long userId) {

    List<ShareRecordMessageOrRequest> requests =
        communicationDao.getShareRecordRequestsByUserId(userId);
    List<ShareRecordMessageOrRequestDTO> results = new ArrayList<>();
    for (ShareRecordMessageOrRequest request : requests) {
      if (request.getStatus().equals(CommunicationStatus.NEW)) {
        ShareRecordMessageOrRequestDTO dto =
            new ShareRecordMessageOrRequestDTO(
                request.getId(),
                request.getRecord(),
                request.getTarget().getEmail(),
                request.getPermission());
        results.add(dto);
      }
    }
    return results;
  }

  /**
   * Helper method with common code param share
   *
   * @param subject
   * @param recordOrNotebook
   * @param groupShareCfgs
   * @param share true= sharing, false = unsharing
   * @return a list of created/removed recordGroupSharings
   * @throws IllegalAddChildOperation
   */
  private List<RecordGroupSharing> doRecordOrNotebookShare(
      User subject, BaseRecord recordOrNotebook, ShareConfigElement[] groupShareCfgs, boolean share)
      throws IllegalAddChildOperation {

    List<RecordGroupSharing> sharees = new ArrayList<>();

    // iterate over the groups/users we're going to share with:
    for (ShareConfigElement grpShareCfg : groupShareCfgs) {

      // user or group we are sharing with
      AbstractUserOrGroupImpl userOrGroup = getUserOrGroupToShareWith(grpShareCfg);

      // determine if already shared
      boolean isAlreadyShared = calculateIfAlreadyShared(userOrGroup, recordOrNotebook, subject);
      // skip if attempting to share an already-shared record, or unshare a record that is not
      // shared
      if (share && isAlreadyShared || !share && !isAlreadyShared) {
        log.warn(
            " Doc [{}] will not be shared with [{}] as is already shared",
            recordOrNotebook.getId(),
            userOrGroup.getDisplayName());
        continue;
      }

      doSharing(subject, grpShareCfg, share, recordOrNotebook, userOrGroup, isAlreadyShared)
          .stream()
          .forEach(sharees::add);
    }

    return sharees;
  }

  /**
   * @param grpShareCfg
   * @return
   */
  private AbstractUserOrGroupImpl getUserOrGroupToShareWith(ShareConfigElement grpShareCfg) {
    AbstractUserOrGroupImpl userOrGroup = null;
    if (grpShareCfg.getGroupid() != null) {
      userOrGroup = grpDao.get(grpShareCfg.getGroupid());
    } else if (grpShareCfg.getUserId() != null) {
      userOrGroup = userDao.get(grpShareCfg.getUserId());
    } else {
      throw new IllegalStateException();
    }
    return userOrGroup;
  }

  /**
   * @param subject
   * @param groupShareCfg
   * @param share
   * @param docOrNotebook
   * @param toShareWith
   * @param isAlreadyShared
   * @return list of created/removed RecordGroupSharing entries; when sharing, this will be just one
   *     entry, when unsharing could be more than one e.g. if unsharing a notebook with entries that
   *     were shared separately;
   * @throws IllegalAddChildOperation
   */
  private List<RecordGroupSharing> doSharing(
      User subject,
      ShareConfigElement groupShareCfg,
      boolean share,
      BaseRecord docOrNotebook,
      AbstractUserOrGroupImpl toShareWith,
      boolean isAlreadyShared)
      throws IllegalAddChildOperation {

    String usernameInSession = SecurityUtils.getSubject().getPrincipal().toString();
    User userInSession = userDao.getUserByUsername(usernameInSession);
    boolean isGroupShare = toShareWith.isGroup();

    if (isGroupShare) {
      assertCanDoSharingWithGroup(subject, share, docOrNotebook, toShareWith, userInSession);
    } else {
      assertCanDoSharingWithUser(subject, share, docOrNotebook, toShareWith, userInSession);
    }

    /* unshare operation handled by a separate method */
    if (!share) {
      log.info("Unsharing doc [{}] with [{}]", docOrNotebook.getId(), toShareWith.getDisplayName());
      return doUnshare(subject, groupShareCfg, docOrNotebook, toShareWith);
    }

    log.info("Sharing doc [{}] with [{}]", docOrNotebook.getId(), toShareWith.getDisplayName());

    /* calculate target folder for this doSharing run; target folder may be specified by the user
     * when sharing with group, otherwise defaults to top-level of individual/group share folder */
    Folder selectedTargetFolder = null;
    boolean isShareWithAnonyomusUser = false;
    if (isGroupShare) {
      checkUserCanShareWithGroup(subject, toShareWith.asGroup());
      selectedTargetFolder =
          getGroupShareTargetFolder(subject, groupShareCfg, docOrNotebook, toShareWith);
    } else {
      User user = initUserAndCheckUserCanShareWithOtherUser(subject, toShareWith.getId());
      if (user.hasRole(Role.ANONYMOUS_ROLE)) {
        isShareWithAnonyomusUser = true;
      } else {
        selectedTargetFolder = getTopLevelSharingFolder(subject, toShareWith, user, docOrNotebook);
      }
    }

    /* actual share operation - saving RecordGroupSharing entity */
    RecordGroupSharing rgs = null;
    if (!isAlreadyShared) {
      // sharing record and adding permissions for user or group
      rgs = new RecordGroupSharing(toShareWith, docOrNotebook);
      rgs.setPublicationSummary(groupShareCfg.getPublicationSummary());
      rgs.setDisplayContactDetails(groupShareCfg.isDisplayContactDetails());
      rgs.setPublishOnInternet(groupShareCfg.isPublishOnInternet());
      rgs.setSharedBy(userInSession);
      if (selectedTargetFolder != null && selectedTargetFolder.isNotebook()) {
        // when sharing into notebook, we only share once
        if (docOrNotebook.getParentNotebooks().contains(selectedTargetFolder)) {
          return Collections.emptyList();
        }
        rgs.setTargetFolder(selectedTargetFolder);
      }
      rgs = groupshareRecordDao.save(rgs);
      PermissionType permType = permissnUtils.createFromString(groupShareCfg.getOperation());
      rgs.setPermType(permType);
      toShareWith.addPermission(
          permFac.createIdPermission(PermissionDomain.RECORD, permType, docOrNotebook.getId()));
      saveUserOrGroup(toShareWith);
      permissnUtils.refreshCache();
      ConstraintBasedPermission perm =
          new ConstraintBasedPermission(PermissionDomain.RECORD, permType);
      ACLElement el = new ACLElement(toShareWith.getUniqueName(), perm);
      propagateACLAddition(docOrNotebook, el);
      if (docOrNotebook.isSnippet()) {
        ACLElement copy =
            new ACLElement(
                toShareWith.getUniqueName(),
                new ConstraintBasedPermission(PermissionDomain.RECORD, PermissionType.COPY));
        propagateACLAddition(docOrNotebook, copy);
      }
      log.info("Added RGS for doc [{}] and [{}]", docOrNotebook.getId(), toShareWith);
    }
    if (docOrNotebook.isTemplate()) {
      setPermissionsOnAttachmentsInSharedTemplates(docOrNotebook, toShareWith, isAlreadyShared);
    }
    if (isShareWithAnonyomusUser) {
      saveRecordOrFolder(docOrNotebook);
    }

    /* adding shared item into shared folder */
    if (selectedTargetFolder != null) {
      ACLPropagationPolicy aclPolicy = ACLPropagationPolicy.DEFAULT_POLICY;
      if (selectedTargetFolder.isNotebook()) {
        aclPolicy = ACLPropagationPolicy.SHARE_INTO_NOTEBOOK_POLICY;
      }
      selectedTargetFolder.addChild(docOrNotebook, ChildAddPolicy.DEFAULT, subject, aclPolicy);
      saveRecordOrFolder(docOrNotebook);
      folderDao.save(selectedTargetFolder);
      log.info(
          "Added RTF for doc [{}] and folder [{}]",
          docOrNotebook.getId(),
          selectedTargetFolder.getId());
    }

    return List.of(rgs);
  }

  private Folder getGroupShareTargetFolder(
      User subject,
      ShareConfigElement groupShareCfg,
      BaseRecord docOrNotebook,
      AbstractUserOrGroupImpl targetGroup) {

    Long folderToShareIntoId = groupShareCfg.getGroupFolderId();
    Folder topLevelSharingFolder =
        getTopLevelSharingFolder(subject, targetGroup, null, docOrNotebook);

    /* if no target folder provided, share into top level */
    if (folderToShareIntoId == null) {
      return topLevelSharingFolder;
    }

    Folder targetFolder = folderDao.get(folderToShareIntoId);
    if (targetFolder.getShortestPathToParent(topLevelSharingFolder).isEmpty()) {
      log.warn(
          "Folder to share into ({}, id {}), is not the correct folder for this group "
              + "folder {}, using group folder",
          targetFolder.getName(),
          targetFolder.getId(),
          topLevelSharingFolder.getId());
      targetFolder = topLevelSharingFolder;
    }
    // can only share to notebooks that were shared with "write" permission
    if (targetFolder.isNotebook()) {
      assertUserHasWritePermission(subject, targetFolder);
      if (targetFolder.getOwner().equals(docOrNotebook.getOwner())) {
        throw new IllegalAddChildOperation("can't share document into own notebook");
      }
    }
    return targetFolder;
  }

  private void assertCanDoSharingWithUser(
      User subject,
      boolean share,
      BaseRecord docOrNotebook,
      AbstractUserOrGroupImpl toShareWith,
      User userInSession) {

    boolean sysAdminUnsharingAPublicLink = false;
    boolean communityAdminUnsharingACommunityMemberPublication = false;
    if (((User) toShareWith).hasRole(Role.ANONYMOUS_ROLE) && (userInSession.hasSysadminRole())) {
      sysAdminUnsharingAPublicLink = true;
    } else if (((User) toShareWith).hasRole(Role.ANONYMOUS_ROLE) && userInSession.hasAdminRole()) {
      List<Community> communitiesManagedByAdmin =
          communityService.listCommunitiesForAdmin(userInSession.getId());
      RecordGroupSharing publication =
          groupshareRecordDao.getRecordGroupSharingsForRecord(docOrNotebook.getId()).stream()
              .filter(f -> f.getPublicLink() != null)
              .findFirst()
              .get();
      List<Community> publisherCommunities =
          communityService.listCommunitiesForUser(publication.getSharedBy().getId());
      Set<Community> inCommon =
          communitiesManagedByAdmin.stream()
              .distinct()
              .filter(publisherCommunities::contains)
              .collect(Collectors.toSet());
      if (!inCommon.isEmpty()) {
        communityAdminUnsharingACommunityMemberPublication = true;
      }
    }

    // Checking if the user in session is the target, or if its admin unsharing
    // in these cases we don't need to check permissions.
    if (!userInSession.getUsername().equals(toShareWith.asUser().getUsername())
        && !sysAdminUnsharingAPublicLink
        && !communityAdminUnsharingACommunityMemberPublication) {
      assertHasSharePermission(subject, docOrNotebook, share);
    }
  }

  private void assertCanDoSharingWithGroup(
      User subject,
      boolean share,
      BaseRecord docOrNotebook,
      AbstractUserOrGroupImpl toShareWith,
      User userInSession) {

    boolean isLabAdminWithViewAll =
        toShareWith.asGroup().getLabAdminsWithViewAllPermission().contains(userInSession);
    boolean isCommAdmin = false;
    // community might be null for collaboration groups
    if (!toShareWith.asGroup().isCollaborationGroup()
        && toShareWith.asGroup().getCommunity() != null) {
      isCommAdmin = toShareWith.asGroup().getCommunity().getAdmins().contains(userInSession);
    }

    if (!isLabAdminWithViewAll && !isCommAdmin && !userInSession.hasSysadminRole()) {
      assertHasSharePermission(subject, docOrNotebook, share);
    }
  }

  private void assertUserHasWritePermission(User subject, Folder toShareInto) {
    boolean canWriteToSharedFolder =
        permissnUtils.isPermitted(toShareInto, PermissionType.WRITE, subject);
    if (!canWriteToSharedFolder) {
      throw new AuthorizationException(
          subject.getFullName() + " made unauthorised attempt to share a record");
    }
  }

  private void setPermissionsOnAttachmentsInSharedTemplates(
      BaseRecord documentOrNotebook, AbstractUserOrGroupImpl toShareWith, boolean isAlreadyShared) {
    for (Field field : documentOrNotebook.asStrucDoc().getFields()) {
      for (FieldAttachment attachment : field.getLinkedMediaFiles()) {
        EcatMediaFile emf = attachment.getMediaFile();
        ConstraintBasedPermission readPerm =
            new ConstraintBasedPermission(PermissionDomain.RECORD, PermissionType.READ);
        boolean needToSave = false;
        /* if this group isn't already shared with this record */
        if (!isAlreadyShared) {
          boolean added =
              emf.getSharingACL()
                  .addACLElement(new ACLElement(toShareWith.getUniqueName(), readPerm));
          if (added && !needToSave) {
            needToSave = true;
          }
        }
        // just save if there is at least 1 new ACL to add.
        if (needToSave) {
          recordDao.save(emf);
        }
      }
    }
  }

  /**
   * @param subject
   * @param groupShareCfg
   * @param docOrNotebook
   * @param toUnshareWith
   * @return list of the deleted RecordGroupSharing entries; may be more than 1 for a single unshare
   *     action, e.g. when unsharing a notebook with separately shared entries;
   */
  private List<RecordGroupSharing> doUnshare(
      User subject,
      ShareConfigElement groupShareCfg,
      BaseRecord docOrNotebook,
      AbstractUserOrGroupImpl toUnshareWith) {

    List<RecordGroupSharing> unshared = new ArrayList<>();
    List<BaseRecord> originalItemsToUnshare = TransformerUtils.toList(docOrNotebook);
    // there may be more items to unshare, e.g. shared entries of a shared notebook
    List<BaseRecord> otherItemsToUnshare = new ArrayList<>();
    if (toUnshareWith.isGroup()
        || (toUnshareWith.isUser() && !toUnshareWith.asUser().hasRole(Role.ANONYMOUS_ROLE))) {
      // populate entries of a notebook for unshare but not for unpublish
      otherItemsToUnshare = addOtherItemsToUnshare(docOrNotebook, toUnshareWith);
    }

    // probably best to put entries at front of list, so these are deleted first.
    otherItemsToUnshare.addAll(originalItemsToUnshare);
    for (BaseRecord toUnshare : otherItemsToUnshare) {
      Optional<RecordGroupSharing> rgsToDelete =
          groupshareRecordDao.findByRecordAndUserOrGroup(toUnshareWith.getId(), toUnshare.getId());
      if (rgsToDelete.isPresent()) {
        unshared.add(rgsToDelete.get());
        groupshareRecordDao.removeRecordFromGroupShare(toUnshareWith.getId(), toUnshare.getId());
      }
      PermissionType permType = permissnUtils.createFromString(groupShareCfg.getOperation());
      toUnshareWith.removePermission(
          permFac.createIdPermission(PermissionDomain.RECORD, permType, toUnshare.getId()));
      saveUserOrGroup(toUnshareWith);
      permissnUtils.refreshCache();
      if (toUnshareWith.isUser() && toUnshare.isNotebook()) {

        RecordSharingACL acl = toUnshare.getSharingACL();
        Set<ACLElement> removed = acl.removeACLsforUserOrGroup(toUnshareWith);
        for (ACLElement el : removed) {
          propagateACLRemoval(toUnshare, el);
        }
        folderDao.save((Folder) toUnshare);

      } else {
        ConstraintBasedPermission perm =
            new ConstraintBasedPermission(PermissionDomain.RECORD, permType);
        ACLElement el = new ACLElement(toUnshareWith.getUniqueName(), perm);
        propagateACLRemoval(toUnshare, el);
        if (toUnshare.isSnippet()) {
          ConstraintBasedPermission copyPerm =
              new ConstraintBasedPermission(PermissionDomain.RECORD, PermissionType.COPY);
          ACLElement copyEl = new ACLElement(toUnshareWith.getUniqueName(), copyPerm);
          propagateACLRemoval(toUnshare, copyEl);
        }
      }

      // now we remove this shared item from its location in user's/group shared folder
      Folder sharedTopLevelFolder = null;
      if (toUnshareWith.isUser()) {
        User user = toUnshareWith.asUser();
        if (user.getSharedFolder() != null) {
          sharedTopLevelFolder =
              getTopLevelSharingFolder(subject, toUnshareWith, user, docOrNotebook);
        }
      } else {
        sharedTopLevelFolder =
            getTopLevelSharingFolder(subject, toUnshareWith, null, docOrNotebook);
      }

      RSPath path = toUnshare.getShortestPathToParent(sharedTopLevelFolder);
      Optional<Folder> sharedParent = path.getImmediateParentOf(toUnshare);
      if (sharedParent.isPresent()) {
        removeUnsharedChild(sharedParent.get(), toUnshare);
        log.info(
            "Removed RTF for doc [{}] and folder [{}]",
            docOrNotebook.getId(),
            sharedParent.get().getId());
      }
    }
    return unshared;
  }

  private void removeUnsharedChild(Folder sharedParent, BaseRecord toUnshare) {
    sharedParent.removeChild(toUnshare);
    folderDao.save(sharedParent);
    saveRecordOrFolder(toUnshare);
  }

  /**
   * @param documentOrNotebook
   * @param toShareWith
   * @return
   */
  private List<BaseRecord> addOtherItemsToUnshare(
      BaseRecord documentOrNotebook, AbstractUserOrGroupImpl toShareWith) {
    List<BaseRecord> rc = new ArrayList<>();
    // if we're not a notebook, we just return an empty list
    if (!documentOrNotebook.isNotebook()) {
      return rc;
    }
    // now see if this notebook has entries shared with other people
    Notebook n = (Notebook) documentOrNotebook;
    if (n.getChildren().isEmpty()) {
      // if notebook has no entries, short-circuit.
      return rc;
    }
    List<Long> entryIds =
        TransformerUtils.transform(n.getChildrens(), UniquelyIdentifiable.OBJECT_TO_ID_TRANSFORMER);

    List<BaseRecord> sharedEntries =
        groupshareRecordDao.findRecordsSharedWithUserOrGroup(toShareWith.getId(), entryIds);
    return sharedEntries;
  }

  /**
   * @param user
   * @param otherUserId
   * @return
   */
  private User initUserAndCheckUserCanShareWithOtherUser(User user, Long otherUserId) {
    User otherUser = userDao.get(otherUserId);
    if (otherUser.hasRole(Role.ANONYMOUS_ROLE)) {
      return otherUser;
    }
    Folder userRoot = otherUser.getRootFolder();
    // Need to reload user after initializing, to set RF id and shared folder
    if (userRoot == null) {
      userRoot = contentInitializer.init(otherUser.getId()).getUserRoot();
      userDao.save(otherUser);
    }
    userRoot.getName();

    // In this point, The user may not belong to any
    // group in a cloud environment. So we do not check share
    // permission within a group.
    if (!properties.isCloud()) {
      checkUserCanShareWithOtherUser(user, otherUser);
    }
    return otherUser;
  }

  /**
   * This method checks if the user can share their records with given group. That boils down to
   * group membership, i.e. users can share with groups they belong to.
   *
   * @param user
   * @param group
   * @throws AuthorizationException if user not a member of group they try to share with
   */
  private void checkUserCanShareWithGroup(User user, Group group) {
    boolean canShareWithGroup = user.getGroups().contains(group);
    if (!canShareWithGroup) {
      throw new AuthorizationException(
          "Unauthorized attempt by ["
              + user.getUsername()
              + "] to share with group ["
              + group.getUniqueName()
              + "]");
    }
  }

  /**
   * This method checks if the user can individually share their records with another. Outside of
   * RSpace Community that boils down to group membership, i.e. users can share with anyone in the
   * group they belong to.
   *
   * @param user
   * @param otherUser
   * @throws AuthorizationException if other user not a member of any group with user
   */
  private void checkUserCanShareWithOtherUser(User user, User otherUser)
      throws AuthorizationException {
    Set<Group> groups = user.getGroups();
    List<String> validUsernames =
        Group.getUniqueUsersInGroups(groups, null).stream()
            .map(User::getUsername)
            .collect(Collectors.toList());

    if (!validUsernames.contains(otherUser.getUsername())) {
      throw new AuthorizationException(
          "Unauthorized attempt by ["
              + user.getUsername()
              + "] to share with user ["
              + otherUser.getUsername()
              + "]");
    }
  }

  /**
   * Gets the correct sharing folder to insert the shared record into
   *
   * @param subject
   * @param sharee
   * @param user
   * @return
   * @throws IllegalAddChildOperation
   */
  private Folder getTopLevelSharingFolder(
      User subject, AbstractUserOrGroupImpl sharee, User user, BaseRecord docOrNotebook)
      throws IllegalAddChildOperation {

    Folder shared = null;
    if (sharee.isUser()) {
      shared = getOrCreateIndividualSharedFolderForUsers(subject, user, docOrNotebook);
    } else {
      if (docOrNotebook.isSnippet()) {
        shared = folderDao.getSharedSnippetFolderForGroup(sharee.asGroup());
      } else {
        shared = folderDao.getSharedFolderForGroup(sharee.asGroup());
      }
    }
    return shared;
  }

  private Folder getOrCreateIndividualSharedFolderForUsers(
      User sharer, User sharee, BaseRecord docOrNotebook) throws IllegalAddChildOperation {
    Folder individShrdFolder =
        folderDao.getIndividualSharedFolderForUsers(sharer, sharee, docOrNotebook);
    Folder parentfolder = null;
    Folder parentfolder1 = null;
    if (docOrNotebook != null && docOrNotebook.isSnippet()) {
      if (individShrdFolder == null) {
        individShrdFolder = recFactory.createIndividualSharedSnippetsFolder(sharer, sharee);
      }
      permFac.setUpACLForIndividSharedFolder(sharer, sharee, individShrdFolder);
      parentfolder =
          folderDao.getSystemFolderForUserByName(
              sharer,
              UserFolderCreator.SHARED_SNIPPETS_FOLDER_PREFIX
                  + Folder.INDIVIDUAL_SHARE_ITEMS_FLDER_NAME);
      parentfolder1 =
          folderDao.getSystemFolderForUserByName(
              sharee,
              UserFolderCreator.SHARED_SNIPPETS_FOLDER_PREFIX
                  + Folder.INDIVIDUAL_SHARE_ITEMS_FLDER_NAME);
      if (parentfolder1
          == null) { // sharee has not logged in to RSpace since shared snippets (RSPAC-2660) was
        // deployed
        userContentUpdater.doUserContentUpdates(sharee);
        parentfolder1 =
            folderDao.getSystemFolderForUserByName(
                sharee,
                UserFolderCreator.SHARED_SNIPPETS_FOLDER_PREFIX
                    + Folder.INDIVIDUAL_SHARE_ITEMS_FLDER_NAME);
      }
    } else {
      if (individShrdFolder == null) {
        individShrdFolder = recFactory.createIndividualSharedFolder(sharer, sharee);
      }
      permFac.setUpACLForIndividSharedFolder(sharer, sharee, individShrdFolder);
      parentfolder = folderDao.getIndividualSharedItemsFolderForUser(sharer);
      parentfolder1 = folderDao.getIndividualSharedItemsFolderForUser(sharee);
    }

    folderMgr.addChild(
        parentfolder.getId(), individShrdFolder, sharer, ACLPropagationPolicy.NULL_POLICY);
    folderMgr.addChild(
        parentfolder1.getId(), individShrdFolder, sharee, ACLPropagationPolicy.NULL_POLICY);
    return individShrdFolder;
  }

  /**
   * @param subject
   * @param docOrNbook
   * @param share
   */
  private void assertHasSharePermission(User subject, BaseRecord docOrNbook, boolean share) {

    if (docOrNbook.isStructuredDocument() || docOrNbook.isNotebook()) {

      boolean isPermitted = permissnUtils.isPermitted(docOrNbook, PermissionType.SHARE, subject);
      // if we're sharing, an don't have share permission, complain
      if (!isPermitted) {
        if (share) {
          throw new AuthorizationException(
              subject.getUsername() + " made unauthorised attempt to share a record");
        }
        // we're unsharing.
        // we might be a labdmin deleting a shared record - we have
        // delete, but not share permission, and unsharing can be
        // side-effect of deletion.
        if (!permissnUtils.isPermitted(docOrNbook, PermissionType.DELETE, subject)) {
          throw new AuthorizationException(
              subject.getUsername() + " made unauthorised attempt to unshare a record");
        }
      }
    }
  }

  /**
   * @param userOrGroup
   */
  protected void saveUserOrGroup(AbstractUserOrGroupImpl userOrGroup) {
    if (userOrGroup.isGroup()) {
      grpDao.save(userOrGroup.asGroup());
    } else if (userOrGroup.isUser()) {
      userDao.save(userOrGroup.asUser());
    }
  }

  /**
   * Removes ACLs from subfolders
   *
   * @param documentOrNotebook
   * @param el
   */
  private void propagateACLRemoval(BaseRecord documentOrNotebook, ACLElement el) {
    documentOrNotebook.getSharingACL().removeACLElement(el);
    saveRecordOrFolder(documentOrNotebook);
    if (!el.getUserOrGrpUniqueName().equals(RecordGroupSharing.ANONYMOUS_USER)) {
      for (BaseRecord child : documentOrNotebook.getChildrens()) {
        propagateACLRemoval(child, el);
      }
    }
  }

  /**
   * @param recordOrFolder
   * @param el
   */
  private void propagateACLAddition(BaseRecord recordOrFolder, ACLElement el) {
    recordOrFolder.getSharingACL().addACLElement(el);
    saveRecordOrFolder(recordOrFolder);
    if (!el.getUserOrGrpUniqueName().equals(RecordGroupSharing.ANONYMOUS_USER)) {
      for (BaseRecord child : recordOrFolder.getChildrens()) {
        propagateACLAddition(child, el);
      }
    }
  }

  /**
   * @param recordOrFolder
   */
  private void saveRecordOrFolder(BaseRecord recordOrFolder) {
    if (recordOrFolder.isFolder()) {
      folderDao.save((Folder) recordOrFolder);
    } else {
      recordDao.save((Record) recordOrFolder);
    }
  }

  /**
   * @param g
   * @param groupShareCfgs
   * @return
   */
  private PermissionType getOpForGroup(
      AbstractUserOrGroupImpl g, ShareConfigElement[] groupShareCfgs) {
    for (ShareConfigElement gsc : groupShareCfgs) {
      if (gsc.getId().equals(g.getId())) {
        return permissnUtils.createFromString(gsc.getOperation());
      }
    }
    // fall-through default
    return PermissionType.READ;
  }

  private Long getGroupFolderSelectedAsTarget(
      AbstractUserOrGroupImpl g, ShareConfigElement[] groupShareCfgs) {
    for (ShareConfigElement gsc : groupShareCfgs) {
      if (gsc.getId().equals(g.getId()) && g.isGroup()) {
        return gsc.getGroupFolderId();
      }
    }
    return null;
  }

  /**
   * @param userOrGroup
   * @param record
   * @param subject
   * @return
   */
  private boolean calculateIfAlreadyShared(
      AbstractUserOrGroupImpl userOrGroup, BaseRecord record, User subject) {
    return !docSharedStatusCalculator.canShare(userOrGroup, record, subject);
  }

  /**
   * Convenience method of getting record or notebook
   *
   * @param id
   * @return
   */
  private BaseRecord getRecordOrNotebook(Long id) {
    Optional<Record> optional = recordDao.getSafeNull(id);
    if (optional.isPresent()) {
      return optional.get();
    } else {
      return folderDao.get(id);
    }
  }

  /**
   * @param rgs
   * @param userOrGroup
   * @param toUpdate
   * @param newPerm
   */
  private void updateACLPermissions(
      RecordGroupSharing rgs,
      AbstractUserOrGroupImpl userOrGroup,
      ConstraintBasedPermission toUpdate,
      ConstraintBasedPermission newPerm) {

    BaseRecord br = rgs.getShared();
    // need to create new permission objects as ACL permissions don't have
    // any constraints beyond domain/type - we only want to compare at this level
    ConstraintBasedPermission oldACLEl =
        new ConstraintBasedPermission(toUpdate.getDomain(), toUpdate.getActions());
    ACLElement toRemove = new ACLElement(userOrGroup.getUniqueName(), oldACLEl);
    propagateACLRemoval(br, toRemove);

    ConstraintBasedPermission newACLEl =
        new ConstraintBasedPermission(newPerm.getDomain(), newPerm.getActions());
    ACLElement toAdd = new ACLElement(userOrGroup.getUniqueName(), newACLEl);
    propagateACLAddition(br, toAdd);
  }

  @Override
  public void unshareFromSharedFolder(User deleting, BaseRecord toDelete, RSPath path) {
    AbstractUserOrGroupImpl toUnshareFrom = findUserOrGroupFromPath(deleting, path);
    if (shareeIsDeletingNotebookFromIndividualFolder(deleting, toDelete, toUnshareFrom)) {
      toUnshareFrom = deleting;
    }
    ConstraintBasedPermission toUpdate =
        permissnUtils.findBy(
            toUnshareFrom.getPermissions(),
            PermissionDomain.RECORD,
            new IdConstraint(toDelete.getId()));
    String permittedAction = null;
    if (toUpdate != null) {
      permittedAction = toUpdate.getActions().iterator().next().toString();
    } else {
      permittedAction = "READ";
    }
    ShareConfigElement configEl = new ShareConfigElement(toUnshareFrom.getId(), permittedAction);
    if (shareeIsDeletingNotebookFromIndividualFolder(deleting, toDelete, toUnshareFrom)) {
      configEl.setUserId(deleting.getId());
      unshareRecord(toDelete.getOwner(), toDelete.getId(), new ShareConfigElement[] {configEl});
    } else {
      if (toUnshareFrom.isUser()) {
        configEl.setUserId(toUnshareFrom.getId());
      }
      unshareRecord(deleting, toDelete.getId(), new ShareConfigElement[] {configEl});
    }
  }

  private boolean shareeIsDeletingNotebookFromIndividualFolder(
      User deleting, BaseRecord toDelete, AbstractUserOrGroupImpl toUnshareFrom) {
    return toUnshareFrom.isUser() && !deleting.equals(toDelete.getOwner());
  }

  private AbstractUserOrGroupImpl findUserOrGroupFromPath(User u, RSPath path) {
    // It will be the group shared folder.
    BaseRecord gpSharedFolder = path.get(2).get();
    if (gpSharedFolder.hasType(RecordType.SHARED_GROUP_FOLDER_ROOT)) {
      Group toUnshareFrom = null;
      for (Group gp : u.getGroups()) {
        if (gp.getCommunalGroupFolderId().equals(gpSharedFolder.getId())) {
          toUnshareFrom = gp;
          break;
        }
      }
      if (toUnshareFrom == null) {
        throw new IllegalStateException(
            " could not identify the group this record belongs to [" + gpSharedFolder + "]");
      }
      return toUnshareFrom;
    } else if (path.get(2).get().hasType(RecordType.INDIVIDUAL_SHARED_FOLDER_ROOT)) {
      User toUnshareWith = identifyOtherUserFromIndividSharedItems(u, path.get(2).get().getName());
      return toUnshareWith;
    }
    return null;
  }

  private User identifyOtherUserFromIndividSharedItems(User deleting, String indFolderName) {
    String[] names = indFolderName.split("-");
    if (names.length != 2) {
      throw new IllegalArgumentException(
          "Individual shared folder names should contain 2 usernames!");
    }
    String otherName = null;
    if (names[0].equals(deleting.getUsername())) {
      otherName = names[1];
    } else if (names[1].equals(deleting.getUsername())) {
      otherName = names[0];
    } else {
      throw new IllegalStateException(
          "'" + indFolderName + "' does not contain the deleter's username");
    }
    return userDao.getUserByUsername(otherName);
  }
}
