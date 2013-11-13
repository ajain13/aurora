import json

from twitter.aurora.config.port_resolver import PortResolver
from twitter.aurora.config.schema.base import MesosJob, MesosTaskInstance
from twitter.aurora.config.thrift import resolve_thermos_config, task_instance_from_job
from twitter.common import log

from gen.twitter.aurora.ttypes import AssignedTask

from pystachio import Ref
from thrift.Thrift import TException
from thrift.TSerialization import deserialize as thrift_deserialize


def assigned_task_from_mesos_task(task):
  """Deserialize AssignedTask from a launchTask task protocol buffer."""
  try:
    assigned_task = thrift_deserialize(AssignedTask(), task.data)
  except (EOFError, TException) as e:
    raise ValueError('Could not deserialize task! %s' % e)
  return assigned_task


def mesos_job_from_assigned_task(assigned_task):
  """Deserialize a MesosJob pystachio struct from an AssignedTask."""
  thermos_task = resolve_thermos_config(assigned_task.task)
  try:
    json_blob = json.loads(thermos_task)
  except (TypeError, ValueError):
    return None
  if 'instance' in json_blob:
    # This is a MesosTaskInstance so we cannot get a MesosJob from this assigned_task
    return None
  return MesosJob.json_loads(thermos_task)


def mesos_task_instance_from_assigned_task(assigned_task):
  """Deserialize MesosTaskInstance from an AssignedTask thrift."""
  thermos_task = resolve_thermos_config(assigned_task.task)

  if not thermos_task:
    raise ValueError('Task did not have a thermos config!')

  try:
    json_blob = json.loads(thermos_task)
  except (TypeError, ValueError) as e:
    raise ValueError('Could not deserialize thermos config: %s' % e)

  # As part of the transition for MESOS-2133, we can send either a MesosTaskInstance
  # or we can be sending a MesosJob.  So handle both possible cases.  Once everyone
  # is using MesosJob, then we can begin to leverage additional information that
  # becomes available such as cluster.
  if 'instance' in json_blob:
    return MesosTaskInstance.json_loads(thermos_task)

  # This is a MesosJob
  mti, refs = task_instance_from_job(MesosJob.json_loads(thermos_task), assigned_task.instanceId)
  for ref in refs:
    # If the ref is {{thermos.task_id}} or a subscope of
    # {{thermos.ports}}, it currently gets bound by the Thermos Runner,
    # so we must leave them unbound.
    #
    # {{thermos.user}} is a legacy binding which we can safely ignore.
    #
    # TODO(wickman) These should be rewritten by the mesos client to use
    # %%style%% replacements in order to allow us to better type-check configs
    # client-side.
    if ref == Ref.from_address('thermos.task_id'):
      continue
    if Ref.subscope(Ref.from_address('thermos.ports'), ref):
      continue
    if ref == Ref.from_address('thermos.user'):
      continue
    raise ValueError('Unexpected unbound refs: %s' % ' '.join(map(str, refs)))
  return mti


def resolve_ports(mesos_task, portmap):
  """Given a MesosTaskInstance and the portmap of resolved ports from the scheduler,
     create a fully resolved map of port name => port number for the thermos
     runner and discovery manager."""
  task_portmap = mesos_task.announce().portmap().get() if mesos_task.has_announce() else {}
  task_portmap.update(portmap)
  task_portmap = PortResolver.resolve(task_portmap)

  for name, port in task_portmap.items():
    if not isinstance(port, int):
      log.warning('Task has unmapped port: %s => %s' % (name, port))

  return dict((name, port) for (name, port) in task_portmap.items() if isinstance(port, int))